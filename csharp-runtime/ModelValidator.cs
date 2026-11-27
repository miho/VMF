using System.Collections;
using System.Globalization;

namespace Vmf.Runtime;

/// <summary>Runs declared constraints and application rules over a stable model graph.</summary>
public sealed class ModelValidator
{
    private readonly ModelReflectionRegistry reflection;
    private readonly Action<ValidationContext>[] objectRules;
    private readonly Action<object, ValidationContext>[] graphRules;
    private readonly bool followReferences;

    private ModelValidator(ModelReflectionRegistry reflection, Action<ValidationContext>[] objectRules,
        Action<object, ValidationContext>[] graphRules, bool followReferences)
    {
        this.reflection = reflection;
        this.objectRules = objectRules;
        this.graphRules = graphRules;
        this.followReferences = followReferences;
    }

    public static Builder NewBuilder(ModelReflectionRegistry reflection) => new(reflection);

    /// <summary>
    /// Validates the containment tree first. When enabled, non-owning targets are then checked
    /// once per object identity. Callbacks run on the supplied view and must leave the graph unchanged.
    /// </summary>
    public ValidationReport Validate(object root)
    {
        var rootBinding = reflection.BindForValidation(root);
        var seen = new HashSet<object>(ReferenceEqualityComparer.Instance);
        var contained = new Stack<Location>();
        var referenced = new Queue<Location>();
        var children = new List<Location>();
        var declared = new List<ValidationIssue>();
        var issues = new List<ValidationDiagnostic>();
        contained.Push(new Location(root, ""));

        while (contained.Count != 0 || referenced.Count != 0)
        {
            var location = contained.Count != 0 ? contained.Pop() : referenced.Dequeue();
            var model = reflection.BindForValidation(location.Model);
            if (!seen.Add(model.Identity)) continue;

            CheckDeclared(model, location.Path, declared, issues);
            if (objectRules.Length != 0)
            {
                var context = new ValidationContext(root, model, location.Path, issues);
                foreach (var rule in objectRules) rule(context);
            }

            children.Clear();
            foreach (var field in model.Schema.Fields)
            {
                var property = field.Snapshot;
                if (property?.IsContainment == true)
                {
                    AddTargets(field, model, location.Path, children.Add);
                }
                else if (followReferences && property?.IsReference == true)
                {
                    AddTargets(field, model, location.Path, referenced.Enqueue);
                }
            }
            for (int index = children.Count - 1; index >= 0; index--) contained.Push(children[index]);
        }

        if (graphRules.Length != 0)
        {
            var context = new ValidationContext(root, rootBinding, "", issues);
            foreach (var rule in graphRules) rule(root, context);
        }
        return new ValidationReport(issues);
    }

    /// <summary>Returns a valid model or throws with the report after a single validation pass.</summary>
    public T RequireValid<T>(T model) where T : class
    {
        var report = Validate(model);
        if (!report.IsValid) throw new ModelValidationException(report);
        return model;
    }

    private static void CheckDeclared(ModelReflectionRegistry.ModelBinding model, string path,
        List<ValidationIssue> declared, List<ValidationDiagnostic> issues)
    {
        if (model.Schema.ValidateLocal is not { } check)
        {
            CheckReflectionMultiplicity(model, path, issues);
            return;
        }
        declared.Clear();
        check(model.Identity, declared);
        foreach (var issue in declared)
        {
            ModelProperty? property = null;
            if (issue.ModelPath.StartsWith("/", StringComparison.Ordinal) && issue.ModelPath.IndexOf('/', 1) < 0)
            {
                string name = issue.ModelPath[1..].Replace("~1", "/", StringComparison.Ordinal)
                    .Replace("~0", "~", StringComparison.Ordinal);
                property = ValidationContext.FindProperty(model, name);
            }
            var multiplicity = issue.ModelCode == "vmf.multiplicity" && property is not null
                ? new MultiplicityViolation(model.Input, property, CountValues(property))
                : null;
            issues.Add(new ValidationDiagnostic(issue.Severity, issue.ModelCode, path + issue.ModelPath,
                issue.Message, model.Input, property, multiplicity));
        }
    }

    private static void CheckReflectionMultiplicity(ModelReflectionRegistry.ModelBinding model, string path,
        List<ValidationDiagnostic> issues)
    {
        foreach (var field in model.Schema.Fields)
        {
            var property = new ModelProperty(model, field);
            int lower = property.LowerBound;
            int upper = property.UpperBound;
            if (lower == 0 && (upper < 0 || !property.IsCollection && upper == 1)) continue;
            int count = CountValues(property);
            if (count >= lower && (upper < 0 || count <= upper)) continue;

            var multiplicity = new MultiplicityViolation(model.Input, property, count);
            string bound = upper < 0 ? "*" : upper.ToString(CultureInfo.InvariantCulture);
            string message = FormattableString.Invariant($"Expected {lower}..{bound} values, found {count}.");
            issues.Add(new ValidationDiagnostic(ValidationSeverity.Error, "vmf.multiplicity",
                ValidationContext.ChildPath(path, property.Name), message, model.Input, property, multiplicity));
        }
    }

    private static int CountValues(ModelProperty property)
    {
        object? value = property.Get();
        if (value is null) return 0;
        if (!property.IsCollection) return 1;
        var entries = ((IEnumerable)value).Cast<object?>();
        return property.Kind is SnapshotPropertyKind.ReferenceList or SnapshotPropertyKind.ContainmentSequence
            ? entries.Count(item => item is not null)
            : entries.Count();
    }

    private static void AddTargets(ModelReflectionRegistry.PropertySchema field, ModelReflectionRegistry.ModelBinding model,
        string path, Action<Location> add)
    {
        object? value = field.Read(model);
        if (value is null) return;
        string propertyPath = ValidationContext.ChildPath(path, field.Field.Name);
        if (!field.Snapshot!.IsCollection)
        {
            add(new Location(value, propertyPath));
            return;
        }
        int index = 0;
        foreach (object? member in (IEnumerable)value)
        {
            if (member is not null)
            {
                add(new Location(member, propertyPath + "/" + index.ToString(CultureInfo.InvariantCulture)));
            }
            index++;
        }
    }

    private readonly record struct Location(object Model, string Path);

    /// <summary>Configures independent validators. A later change to the builder affects only later builds.</summary>
    public sealed class Builder
    {
        private readonly ModelReflectionRegistry reflection;
        private readonly List<Action<ValidationContext>> objectRules = new();
        private readonly List<Action<object, ValidationContext>> graphRules = new();
        private bool followReferences;

        internal Builder(ModelReflectionRegistry reflection)
        {
            this.reflection = reflection ?? throw new ArgumentNullException(nameof(reflection));
        }

        /// <summary>Adds a rule for instances assignable to the given model or read-only type.</summary>
        public Builder WithRule<T>(Action<T, ValidationContext> rule) where T : class
        {
            ArgumentNullException.ThrowIfNull(rule);
            objectRules.Add(context =>
            {
                if (context.Object is T model) rule(model, context);
            });
            return this;
        }

        /// <summary>Adds a root rule that runs after the object rules.</summary>
        public Builder WithGraphRule(Action<object, ValidationContext> rule)
        {
            ArgumentNullException.ThrowIfNull(rule);
            graphRules.Add(rule);
            return this;
        }

        /// <summary>Includes non-owning references. Inverse containers are excluded.</summary>
        public Builder WithFollowReferences(bool enabled = true)
        {
            followReferences = enabled;
            return this;
        }

        public ModelValidator Build() => new(reflection, objectRules.ToArray(), graphRules.ToArray(), followReferences);
    }
}
