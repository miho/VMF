using System.Collections;
using System.Globalization;
using System.Reflection;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Vmf.Runtime;

namespace Vmf.Portable;

/// <summary>Runs the portable VMF scenarios (see conformance/portable/README.md) against the generated C# code.</summary>
/// <remarks>
/// Usage: <c>PortableRunner &lt;scenarios-dir&gt; &lt;unsupported-models&gt; &lt;expected-failures&gt; &lt;results.json&gt; [filter]</c>.
/// Step semantics, output and exit code mirror conformance/portable/java/PortableRunner.java.
/// </remarks>
public static class PortableRunner
{
    internal const string Target = "csharp";
    internal const string BaseNamespace = "Eu.Mihosoft.Vmf.Portable.";

    private static readonly HashSet<string> Capabilities = new(StringComparer.Ordinal)
    {
        "core", "views", "events", "history", "reflection", "equality-modes", "json",
    };

    public static int Main(string[] args)
    {
        string scenarios = args[0];
        var unsupported = ReadUnsupported(args[1]);
        var expectedFailures = ReadExpectedFailures(args[2]);
        string resultsFile = args[3];
        string filter = args.Length > 4 ? args[4] : "";

        var files = Directory.GetFiles(scenarios, "*.json").OrderBy(file => file, StringComparer.Ordinal).ToList();
        var results = new List<Result>();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (string file in files)
        {
            var root = JsonNode.Parse(File.ReadAllText(file))!.AsObject();
            string stem = Path.GetFileNameWithoutExtension(file);
            foreach (var entry in root["scenarios"]?.AsArray() ?? new JsonArray())
            {
                var scenario = entry!.AsObject();
                string id = stem + "/" + Json.Text(scenario["id"]);
                if (!ids.Add(id))
                {
                    throw new InvalidOperationException("Duplicate scenario id " + id);
                }
                if (filter.Length > 0 && !id.Contains(filter, StringComparison.Ordinal))
                {
                    continue;
                }
                string model = scenario.ContainsKey("model") ? Json.Text(scenario["model"]) : Json.Text(root["model"]);
                var requires = (scenario["requires"]?.AsArray() ?? new JsonArray()).Select(node => Json.Text(node)).ToList();
                string module = requires.Count == 0 ? "core" : string.Join("+", requires);
                var result = new Result(id, module);
                if (!requires.All(Capabilities.Contains))
                {
                    result.Status = "skipped";
                    result.Message = "target does not claim [" + string.Join(", ", requires) + "]";
                }
                else if (unsupported.TryGetValue(model, out string? reason))
                {
                    result.Status = "failed";
                    result.Message = "model '" + model + "' was not generated: " + reason;
                }
                else
                {
                    try
                    {
                        new Execution(model).Run(scenario["steps"]?.AsArray() ?? new JsonArray());
                        result.Status = "passed";
                    }
                    catch (Exception error)
                    {
                        result.Status = "failed";
                        result.Message = Describe(error);
                    }
                }
                results.Add(result);
            }
        }
        return Report(results, expectedFailures, resultsFile) ? 0 : 1;
    }

    // ------------------------------------------------------------------------------------------------ reporting

    private sealed class Result(string id, string module)
    {
        public string Id { get; } = id;
        public string Module { get; } = module;
        public string Status { get; set; } = "";
        public string Message { get; set; } = "";
    }

    private static bool Report(List<Result> results, Dictionary<string, string> expectedFailures, string file)
    {
        var unexpectedFailures = new List<string>();
        var unexpectedPasses = new List<string>();
        var modules = new SortedDictionary<string, int[]>(StringComparer.Ordinal);
        int passed = 0, failed = 0, expected = 0, skipped = 0;
        var json = new JsonArray();
        foreach (var result in results)
        {
            if (!modules.TryGetValue(result.Module, out int[]? counts))
            {
                modules[result.Module] = counts = new int[3];
            }
            bool known = expectedFailures.ContainsKey(result.Id);
            switch (result.Status)
            {
                case "passed":
                    passed++;
                    counts[0]++;
                    if (known) unexpectedPasses.Add(result.Id);
                    break;
                case "failed":
                    failed++;
                    counts[1]++;
                    if (known) expected++;
                    else unexpectedFailures.Add(result.Id + ": " + result.Message);
                    break;
                default:
                    skipped++;
                    counts[2]++;
                    break;
            }
            json.Add(new JsonObject
            {
                ["id"] = result.Id,
                ["module"] = result.Module,
                ["status"] = result.Status,
                ["expectedFailure"] = known,
                ["message"] = result.Message,
            });
        }
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(file))!);
        File.WriteAllText(file, json.ToJsonString(new JsonSerializerOptions
        {
            WriteIndented = true,
            Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        }));

        foreach (var result in results.Where(result => result.Status == "failed"))
        {
            Console.WriteLine("  FAIL " + result.Id + (expectedFailures.ContainsKey(result.Id) ? " (expected)" : "")
                + ": " + result.Message);
        }
        Console.WriteLine();
        Console.WriteLine(Target + " portable scenarios by module (passed/failed/skipped):");
        foreach (var (module, counts) in modules)
        {
            Console.WriteLine("  " + module + ": " + counts[0] + "/" + counts[1] + "/" + counts[2]);
        }
        int run = passed + failed;
        Console.WriteLine(Target + ": " + passed + " of " + run + " passed, " + failed + " failed (" + expected
            + " expected), " + skipped + " skipped");
        foreach (string id in unexpectedPasses)
        {
            Console.WriteLine("UNEXPECTED PASS (remove from expected failures): " + id);
        }
        foreach (string failure in unexpectedFailures)
        {
            Console.WriteLine("UNEXPECTED FAILURE: " + failure);
        }
        return unexpectedFailures.Count == 0 && unexpectedPasses.Count == 0;
    }

    private static Dictionary<string, string> ReadUnsupported(string file)
    {
        var models = new Dictionary<string, string>(StringComparer.Ordinal);
        if (File.Exists(file))
        {
            foreach (string line in File.ReadAllLines(file))
            {
                int tab = line.IndexOf('\t');
                if (tab > 0)
                {
                    models[line[..tab]] = line[(tab + 1)..];
                }
            }
        }
        return models;
    }

    private static Dictionary<string, string> ReadExpectedFailures(string file)
    {
        var failures = new Dictionary<string, string>(StringComparer.Ordinal);
        if (File.Exists(file))
        {
            foreach (string line in File.ReadAllLines(file))
            {
                string trimmed = line.Trim();
                if (trimmed.Length == 0 || trimmed.StartsWith('#'))
                {
                    continue;
                }
                int comment = trimmed.IndexOf('#');
                string id = (comment < 0 ? trimmed : trimmed[..comment]).Trim();
                failures[id] = comment < 0 ? "" : trimmed[(comment + 1)..].Trim();
            }
        }
        return failures;
    }

    internal static string Describe(Exception error)
    {
        Exception cause = Unwrap(error);
        string message = cause is ScenarioFailure ? cause.Message : cause.GetType().Name + ": " + cause.Message;
        return Regex.Replace(message, "\\s+", " ");
    }

    internal static Exception Unwrap(Exception error)
    {
        Exception current = error;
        while (current is TargetInvocationException { InnerException: { } inner })
        {
            current = inner;
        }
        return current;
    }
}

/// <summary>A scenario expectation that did not hold.</summary>
internal sealed class ScenarioFailure(string message) : Exception(message);

/// <summary>One received change notification, kept raw until a scenario compares it.</summary>
internal sealed record Event(int Step, object? Source, string Property, string Kind, object? OldValue,
    object? NewValue, List<object?> Elements, List<int> Indices);

/// <summary>JSON helpers that follow Jackson's <c>asText</c> conventions used by the Java runner.</summary>
internal static class Json
{
    internal static string Text(JsonNode? node) => node switch
    {
        null => "",
        JsonValue value when value.GetValueKind() == JsonValueKind.String => value.GetValue<string>(),
        _ => node.ToJsonString(),
    };

    internal static bool Bool(JsonNode? node) =>
        node is JsonValue value && value.GetValueKind() == JsonValueKind.True;

    internal static int Int(JsonNode? node) =>
        node is JsonValue value && value.TryGetValue(out long whole) ? (int)whole
            : node is JsonValue real ? (int)real.GetValue<double>() : 0;

    internal static string Show(JsonNode? node) => node?.ToJsonString() ?? "null";
}

/// <summary>Executes the steps of one scenario against the generated C# model of one namespace.</summary>
internal sealed class Execution(string model)
{
    private static readonly string[] Operations =
    [
        "new", "set", "add", "insert", "remove", "clear", "check", "let", "equal", "notEqual", "nativeEqual",
        "nativeNotEqual", "same", "notSame", "copy", "readOnly", "instanceOf", "notInstanceOf", "listen", "unlisten",
        "events", "record", "recorded", "undoAll", "redoAll", "properties", "annotations", "write", "read",
        "document",
    ];

    private static readonly Dictionary<string, ModelReflectionRegistry> Registries = new(StringComparer.Ordinal);

    private readonly Assembly assembly = typeof(Execution).Assembly;
    private readonly List<string> labelOrder = [];
    private readonly Dictionary<string, object?> labels = new(StringComparer.Ordinal);
    private readonly Dictionary<string, List<Event>> events = new(StringComparer.Ordinal);
    private readonly Dictionary<string, IDisposable> subscriptions = new(StringComparer.Ordinal);
    private readonly Dictionary<string, List<ModelChange>> undone = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> documents = new(StringComparer.Ordinal);
    private int step;

    private string Namespace => PortableRunner.BaseNamespace + char.ToUpperInvariant(model[0]) + model[1..];

    internal void Run(JsonArray steps)
    {
        foreach (var item in steps)
        {
            step++;
            var node = item!.AsObject();
            string operation = Operation(node);
            try
            {
                if (Json.Bool(node["fails"]))
                {
                    ExpectRejection(operation, node);
                }
                else
                {
                    Execute(operation, node);
                }
            }
            catch (ScenarioFailure failure)
            {
                throw new ScenarioFailure("step " + step + " (" + operation + "): " + failure.Message);
            }
            catch (Exception error)
            {
                throw new ScenarioFailure("step " + step + " (" + operation + ") threw " + PortableRunner.Describe(error));
            }
        }
    }

    private static string Operation(JsonObject node)
    {
        foreach (var (name, _) in node)
        {
            if (Operations.Contains(name))
            {
                return name;
            }
        }
        throw new ScenarioFailure("unknown step " + node.ToJsonString());
    }

    private void ExpectRejection(string operation, JsonObject node)
    {
        string before = Fingerprint();
        try
        {
            Execute(operation, node);
        }
        catch (ScenarioFailure)
        {
            throw;
        }
        catch (Exception)
        {
            string after = Fingerprint();
            if (before != after)
            {
                throw new ScenarioFailure("the rejected edit changed the model:\n before " + before + "\n after  " + after);
            }
            return;
        }
        throw new ScenarioFailure("expected the edit to be rejected, but it succeeded");
    }

    private void Execute(string operation, JsonObject node)
    {
        switch (operation)
        {
            case "new":
                Put(Json.Text(node["new"]), Create(Json.Text(node["type"]), node["with"]));
                break;
            case "set":
                Set(Json.Text(node["set"]), node);
                break;
            case "add":
                Add(Json.Text(node["add"]), node, -1);
                break;
            case "insert":
                Add(Json.Text(node["insert"]), node, Json.Int(node["at"]));
                break;
            case "remove":
                Remove(Json.Text(node["remove"]), node);
                break;
            case "clear":
                ModelLists.Clear(MutableList(Json.Text(node["clear"])));
                break;
            case "check":
                Check(Json.Text(node["check"]), node);
                break;
            case "let":
                Put(Json.Text(node["let"]), Resolve(Json.Text(node["path"])));
                break;
            case "equal":
                Pairs(node["equal"]!.AsArray(), (a, b) =>
                {
                    var registry = Registry(a);
                    if (!registry.ContentEquals(a, b) || !registry.ContentEquals(b, a))
                    {
                        throw new ScenarioFailure("expected content equality");
                    }
                    if (registry.ContentHashCode(a!) != Registry(b).ContentHashCode(b!))
                    {
                        throw new ScenarioFailure("content-equal objects have different content hash codes");
                    }
                });
                break;
            case "notEqual":
                Pairs(node["notEqual"]!.AsArray(), (a, b) =>
                {
                    var registry = Registry(a);
                    if (registry.ContentEquals(a, b) || registry.ContentEquals(b, a))
                    {
                        throw new ScenarioFailure("expected content inequality");
                    }
                });
                break;
            case "nativeEqual":
                Pairs(node["nativeEqual"]!.AsArray(), (a, b) =>
                {
                    if (!a!.Equals(b) || !b!.Equals(a) || a.GetHashCode() != b.GetHashCode())
                    {
                        throw new ScenarioFailure("expected native equality with equal hash codes");
                    }
                });
                break;
            case "nativeNotEqual":
                Pairs(node["nativeNotEqual"]!.AsArray(), (a, b) =>
                {
                    if (a!.Equals(b) || b!.Equals(a))
                    {
                        throw new ScenarioFailure("expected native inequality");
                    }
                });
                break;
            case "same":
                Pairs(node["same"]!.AsArray(), (a, b) =>
                {
                    if (!ReferenceEquals(a, b)) throw new ScenarioFailure("expected the same object");
                });
                break;
            case "notSame":
                Pairs(node["notSame"]!.AsArray(), (a, b) =>
                {
                    if (ReferenceEquals(a, b)) throw new ScenarioFailure("expected different objects");
                });
                break;
            case "copy":
            {
                object? source = Resolve(Json.Text(node["copy"]));
                Put(Json.Text(node["as"]), Registry(source).DeepCopy(source!));
                break;
            }
            case "readOnly":
            {
                object source = Resolve(Json.Text(node["readOnly"]))
                    ?? throw new NullReferenceException("cannot create a read-only view of null");
                MethodInfo asReadOnly = source.GetType().GetMethod("AsReadOnly", Type.EmptyTypes)
                    ?? throw new MissingMethodException(source.GetType().Name, "AsReadOnly");
                Put(Json.Text(node["as"]), asReadOnly.Invoke(source, null));
                break;
            }
            case "instanceOf":
            case "notInstanceOf":
            {
                string label = Json.Text(node[operation]);
                string type = Json.Text(node["type"]);
                bool instance = IsInstance(type, Resolve(label));
                if (instance != (operation == "instanceOf"))
                {
                    throw new ScenarioFailure("expected " + label + (instance ? " not " : " ") + "to be a " + type);
                }
                break;
            }
            case "listen":
                Listen(Json.Text(node["listen"]), Json.Text(node["as"]),
                    node.ContainsKey("scope") ? Json.Text(node["scope"]) : "recursive");
                break;
            case "unlisten":
            {
                string name = Json.Text(node["unlisten"]);
                if (!subscriptions.Remove(name, out var subscription))
                {
                    throw new KeyNotFoundException("no listener '" + name + "'");
                }
                subscription.Dispose();
                break;
            }
            case "events":
                CheckEvents(Json.Text(node["events"]), node);
                break;
            case "record":
                Changes(Resolve(Json.Text(node["record"]))).Start();
                break;
            case "recorded":
            {
                int count = Changes(Resolve(Json.Text(node["recorded"]))).HistoryCount;
                int wanted = Json.Int(node["count"]);
                if (count != wanted)
                {
                    throw new ScenarioFailure("expected " + wanted + " recorded changes, found " + count);
                }
                break;
            }
            case "undoAll":
                UndoAll(Json.Text(node["undoAll"]));
                break;
            case "redoAll":
            {
                string label = Json.Text(node["redoAll"]);
                if (!undone.Remove(label, out var changes))
                {
                    throw new KeyNotFoundException("nothing undone for '" + label + "'");
                }
                foreach (var change in changes)
                {
                    change.Redo();
                }
                break;
            }
            case "properties":
            {
                var names = Reflect(Resolve(Json.Text(node["properties"]))).Properties.Select(p => p.Name).ToList();
                var expected = node["are"]!.AsArray().Select(name => Json.Text(name)).ToList();
                if (!names.SequenceEqual(expected))
                {
                    throw new ScenarioFailure("expected properties " + List(expected) + ", found " + List(names));
                }
                break;
            }
            case "annotations":
                CheckAnnotations(Json.Text(node["annotations"]), node["are"]!.AsArray());
                break;
            case "write":
            {
                object root = Resolve(Json.Text(node["write"])) ?? throw new ScenarioFailure("cannot write null");
                bool plain = node["plain"] is JsonNode flag && flag.GetValue<bool>();
                MethodInfo write = plain
                    ? SnapshotMethod("WritePlain", 1).MakeGenericMethod(root.GetType())
                    : SnapshotMethod("Write", 1);
                documents[Json.Text(node["as"])] = (string)Invoke(write, root)!;
                break;
            }
            case "read":
            {
                Type type = ModelType(Json.Text(node["type"]));
                bool plain = node["plain"] is JsonNode flag && flag.GetValue<bool>();
                MethodInfo read = SnapshotMethod(plain ? "ReadPlain" : "Read", 1).MakeGenericMethod(type);
                Put(Json.Text(node["as"]), Invoke(read, Document(node["read"])));
                break;
            }
            case "document":
            {
                string name = Json.Text(node["document"]);
                JsonNode? actual = JsonNode.Parse(Document(node["document"]));
                if (!SameJson(actual, node["is"]))
                {
                    throw new ScenarioFailure(name + ": expected " + Json.Show(node["is"]) + ", written "
                        + Json.Show(actual));
                }
                break;
            }
            default:
                throw new ScenarioFailure("unsupported operation " + operation);
        }
    }

    /// <summary>A written document by name, or a literal {"json": ...} document from the scenario.</summary>
    private string Document(JsonNode? source)
    {
        if (source is JsonValue)
        {
            string name = Json.Text(source);
            return documents.TryGetValue(name, out var text) ? text
                : throw new ScenarioFailure("unknown document '" + name + "'");
        }
        return source?["json"]?.ToJsonString() ?? throw new ScenarioFailure("missing document");
    }

    private MethodInfo SnapshotMethod(string name, int parameters)
    {
        Type snapshot = assembly.GetType(Namespace + ".ModelSnapshot")
            ?? throw new ScenarioFailure("no ModelSnapshot in " + Namespace);
        return snapshot.GetMethods(BindingFlags.Public | BindingFlags.Static)
            .First(method => method.Name == name && method.GetParameters().Length == parameters);
    }

    private static object? Invoke(MethodInfo method, params object?[] arguments)
    {
        try
        {
            return method.Invoke(null, arguments);
        }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw;
        }
    }

    /// <summary>JSON equality: objects ignore key order, numbers compare by value.</summary>
    private static bool SameJson(JsonNode? actual, JsonNode? expected)
    {
        if (expected is null || actual is null) return expected is null && actual is null;
        if (expected is JsonObject expectedObject)
        {
            return actual is JsonObject actualObject && actualObject.Count == expectedObject.Count
                && expectedObject.All(field => actualObject.TryGetPropertyValue(field.Key, out var value)
                    && SameJson(value, field.Value));
        }
        if (expected is JsonArray expectedArray)
        {
            return actual is JsonArray actualArray && actualArray.Count == expectedArray.Count
                && expectedArray.Select((item, index) => SameJson(actualArray[index], item)).All(same => same);
        }
        var left = expected.AsValue();
        var right = actual as JsonValue;
        if (right is null) return false;
        if (left.GetValueKind() == JsonValueKind.Number || right.GetValueKind() == JsonValueKind.Number)
        {
            return left.GetValueKind() == right.GetValueKind()
                && decimal.Parse(left.ToJsonString(), NumberStyles.Float, CultureInfo.InvariantCulture)
                    == decimal.Parse(right.ToJsonString(), NumberStyles.Float, CultureInfo.InvariantCulture);
        }
        return left.ToJsonString() == right.ToJsonString();
    }

    private void Put(string label, object? value)
    {
        if (!labels.ContainsKey(label))
        {
            labelOrder.Add(label);
        }
        labels[label] = value;
    }

    // ---------------------------------------------------------------------------------------- object access

    private Type? FindModelType(string name) => assembly.GetType(Namespace + "." + name);

    private Type ModelType(string name) => FindModelType(name)
        ?? throw new TypeLoadException("no generated type " + Namespace + "." + name);

    /// <summary>
    /// C# maps an interface-only VMF type to an abstract class, plus an <c>I</c>-prefixed interface when a type has
    /// several model supertypes; an immutable type implements its supertypes' read-only interfaces instead.
    /// </summary>
    private bool IsInstance(string name, object? value)
    {
        Type? direct = FindModelType(name);
        Type? contract = FindModelType("I" + name);
        if (direct is null && contract is null)
        {
            throw new TypeLoadException("no generated type " + Namespace + "." + name);
        }
        if (value is null)
        {
            return false;
        }
        if (direct?.IsInstanceOfType(value) == true || contract?.IsInstanceOfType(value) == true)
        {
            return true;
        }
        return value is IImmutableModel && FindModelType("IReadOnly" + name)?.IsInstanceOfType(value) == true;
    }

    private object Create(string type, JsonNode? with)
    {
        Type modelType = ModelType(type);
        if (with is null)
        {
            if (modelType.IsAbstract || modelType.IsInterface)
            {
                // Like `new Named()`, which the C# compiler rejects for an interface-only type.
                return Activator.CreateInstance(modelType)!;
            }
            if (modelType.GetConstructor(Type.EmptyTypes) is { } constructor)
            {
                return constructor.Invoke(null);
            }
            // Immutable types are created by their builders only.
        }
        MethodInfo newBuilder = modelType.GetMethod("NewBuilder", BindingFlags.Public | BindingFlags.Static, Type.EmptyTypes)
            ?? throw new MissingMethodException(modelType.Name, "NewBuilder");
        object builder = newBuilder.Invoke(null, null)!;
        Type builderType = newBuilder.ReturnType;
        if (with is JsonObject fields)
        {
            foreach (var (property, value) in fields)
            {
                ApplyBuilderValue(builder, builderType, property, value);
            }
        }
        MethodInfo build = builderType.GetMethod("Build", Type.EmptyTypes)
            ?? throw new MissingMethodException(builderType.Name, "Build");
        return build.Invoke(builder, null)!;
    }

    private void ApplyBuilderValue(object builder, Type builderType, string property, JsonNode? value)
    {
        string name = "With" + char.ToUpperInvariant(property[0]) + property[1..];
        var attempts = new List<string>();
        foreach (MethodInfo method in builderType.GetMethods(BindingFlags.Public | BindingFlags.Instance))
        {
            if (!string.Equals(method.Name, name, StringComparison.OrdinalIgnoreCase) || method.GetParameters().Length != 1)
            {
                continue;
            }
            Type parameter = method.GetParameters()[0].ParameterType;
            object? argument;
            try
            {
                if (value is JsonArray array && parameter.IsArray)
                {
                    Type element = parameter.GetElementType()!;
                    var items = Array.CreateInstance(element, array.Count);
                    for (int i = 0; i < array.Count; i++)
                    {
                        items.SetValue(Fit(element, ToClr(array[i], element)), i);
                    }
                    argument = items;
                }
                else if (value is JsonArray list && ElementOf(parameter) is { } element)
                {
                    var items = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(element))!;
                    foreach (var item in list)
                    {
                        items.Add(Fit(element, ToClr(item, element)));
                    }
                    argument = items;
                }
                else if (value is not JsonArray && !parameter.IsArray && ElementOf(parameter) is null)
                {
                    argument = Fit(parameter, ToClr(value, parameter));
                }
                else
                {
                    continue;
                }
            }
            catch (ArgumentException)
            {
                attempts.Add(parameter.Name);
                continue;
            }
            method.Invoke(builder, [argument]);
            return;
        }
        throw new ScenarioFailure("no builder method " + name + " accepts " + Json.Show(value)
            + " (tried " + List(attempts) + ")");
    }

    /// <summary>Returns the value if it is assignable to the parameter type; otherwise signals a mismatch.</summary>
    private static object? Fit(Type type, object? value)
    {
        if (value is null || type.IsInstanceOfType(value)
            || Nullable.GetUnderlyingType(type)?.IsInstanceOfType(value) == true)
        {
            return value;
        }
        throw new ArgumentException("type");
    }

    /// <summary>The element type of a sequence parameter, or null for scalars, strings and model objects.</summary>
    private static Type? ElementOf(Type type)
    {
        if (type == typeof(string))
        {
            return null;
        }
        if (type.IsGenericType && type.GetGenericTypeDefinition() == typeof(IEnumerable<>))
        {
            return type.GetGenericArguments()[0];
        }
        return type.GetInterfaces()
            .FirstOrDefault(i => i.IsGenericType && i.GetGenericTypeDefinition() == typeof(IEnumerable<>))
            ?.GetGenericArguments()[0];
    }

    internal object? Resolve(string path)
    {
        var segments = Parse(path);
        if (!labels.TryGetValue(segments[0].Name, out object? current))
        {
            throw new ScenarioFailure("unknown label '" + segments[0].Name + "'");
        }
        current = Index(current, segments[0].Index, path);
        foreach (var segment in segments.Skip(1))
        {
            if (current is null)
            {
                throw new ScenarioFailure("null before '" + segment.Name + "' in " + path);
            }
            current = Index(Property(current, segment.Name).Get(), segment.Index, path);
        }
        return current;
    }

    private static object? Index(object? value, string? index, string path)
    {
        if (index is null)
        {
            return value;
        }
        if (value is not IEnumerable items || value is string)
        {
            throw new ScenarioFailure("cannot index " + path);
        }
        return items.Cast<object?>().ElementAt(int.Parse(index, CultureInfo.InvariantCulture));
    }

    /// <summary>Splits <c>a.b[2].c</c> into (name, index) pairs.</summary>
    private static List<(string Name, string? Index)> Parse(string path)
    {
        var segments = new List<(string, string?)>();
        foreach (string part in path.Split('.'))
        {
            int bracket = part.IndexOf('[');
            segments.Add(bracket < 0 ? (part, null) : (part[..bracket], part[(bracket + 1)..^1]));
        }
        return segments;
    }

    /// <summary>Resolves everything but the last segment and returns (owner, last name, last index).</summary>
    private (object? Owner, string Name, string? Index) Owner(string path)
    {
        int dot = path.LastIndexOf('.');
        if (dot < 0)
        {
            throw new ScenarioFailure("expected owner.property in " + path);
        }
        var last = Parse(path[(dot + 1)..])[0];
        return (Resolve(path[..dot]), last.Name, last.Index);
    }

    private ModelProperty Property(object? owner, string name) =>
        Reflect(owner).FindProperty(name)
        ?? throw new ScenarioFailure(owner!.GetType().Name + " has no property '" + name + "'");

    private ModelReflection Reflect(object? value) => Registry(value).Reflect(value!);

    private static bool IsModelObject(object? value) =>
        value is not null and not string && value.GetType().Namespace is { } ns
        && ns.StartsWith(PortableRunner.BaseNamespace, StringComparison.Ordinal);

    /// <summary>The reflection registry generated for the namespace of a model object or read-only view.</summary>
    private static ModelReflectionRegistry Registry(object? value)
    {
        if (!IsModelObject(value))
        {
            throw new ScenarioFailure("not a model object: " + (value ?? "null"));
        }
        string ns = value!.GetType().Namespace!;
        lock (Registries)
        {
            if (!Registries.TryGetValue(ns, out var registry))
            {
                Type snapshot = value.GetType().Assembly.GetType(ns + ".ModelSnapshot")
                    ?? throw new TypeLoadException("no ModelSnapshot in " + ns);
                registry = (ModelReflectionRegistry)snapshot.GetProperty("Reflection")!.GetValue(null)!;
                Registries[ns] = registry;
            }
            return registry;
        }
    }

    /// <summary>The change stream of a model object, or of a read-only view's own projected stream.</summary>
    private static ChangeStream Changes(object? value)
    {
        if (!IsModelObject(value))
        {
            throw new ScenarioFailure("not a model object: " + (value ?? "null"));
        }
        // Mutable models may also implement their supertypes' read-only interfaces; only views and immutable
        // objects use the projected read-only stream.
        return value is not IModelReadOnlyProjection && value is IReadOnlyModelView view
            ? new ChangeStream(null, view.VmfChanges)
            : new ChangeStream(ModelChanges.For(value!), null);
    }

    private sealed class ChangeStream(ModelChanges? mutable, ReadOnlyModelChanges? view)
    {
        internal ModelChanges Mutable => mutable
            ?? throw new InvalidOperationException("a read-only view has no undoable history");

        internal int HistoryCount => mutable?.History.Count ?? view!.History.Count;

        internal void Start()
        {
            if (mutable is not null) mutable.Start();
            else view!.Start();
        }

        internal IDisposable Listen(Func<ModelChange, Event> local, Func<ReadOnlyModelChange, Event> projected,
            List<Event> received, ModelChangeScope scope) => mutable is not null
            ? mutable.AddListener(change => received.Add(local(change)), scope)
            : view!.AddListener(change => received.Add(projected(change)), scope);
    }

    private object MutableList(string path)
    {
        object? value = Resolve(path);
        if (value is not IEnumerable || value is string)
        {
            throw new ScenarioFailure(path + " is not a list");
        }
        return value;
    }

    // ---------------------------------------------------------------------------------------- edits

    private void Set(string path, JsonObject node)
    {
        var (owner, name, index) = Owner(path);
        ModelProperty property = Property(owner, name);
        if (index is not null)
        {
            object list = property.Get() ?? throw new NullReferenceException(name + " is null");
            ModelLists.Set(list, int.Parse(index, CultureInfo.InvariantCulture), Single(node, property.ValueType));
            return;
        }
        property.Set(Single(node, property.ValueType));
    }

    private void Add(string path, JsonObject node, int index)
    {
        var (owner, name, _) = Owner(path);
        ModelProperty property = Property(owner, name);
        object list = property.Get() ?? throw new NullReferenceException(name + " is null");
        if (node.ContainsKey("objs") || node.ContainsKey("values"))
        {
            ModelLists.AddRange(list, index, Many(node, property.ValueType));
        }
        else if (index < 0)
        {
            ModelLists.Add(list, Single(node, property.ValueType));
        }
        else
        {
            ModelLists.Insert(list, index, Single(node, property.ValueType));
        }
    }

    private void Remove(string path, JsonObject node)
    {
        object list = MutableList(path);
        if (node.ContainsKey("at"))
        {
            ModelLists.RemoveAt(list, Json.Int(node["at"]));
            return;
        }
        bool identity = node.ContainsKey("obj");
        object? target = identity ? Object(node["obj"]) : null;
        int position = 0;
        foreach (object? element in (IEnumerable)list)
        {
            if (identity ? ReferenceEquals(element, target) : SameScalar(element, node["value"]))
            {
                ModelLists.RemoveAt(list, position);
                return;
            }
            position++;
        }
        throw new ScenarioFailure("nothing to remove from " + path);
    }

    private object? Single(JsonObject node, Type type)
    {
        if (node.ContainsKey("obj"))
        {
            return Object(node["obj"]);
        }
        if (node.ContainsKey("value"))
        {
            return ToClr(node["value"], type);
        }
        throw new ScenarioFailure("missing 'obj' or 'value'");
    }

    private List<object?> Many(JsonObject node, Type type) => node.ContainsKey("objs")
        ? node["objs"]!.AsArray().Select(Object).ToList()
        : node["values"]!.AsArray().Select(value => ToClr(value, type)).ToList();

    private object? Object(JsonNode? label) => label is null ? null : Resolve(Json.Text(label));

    /// <summary>Converts a JSON value; <c>{"obj": label}</c> denotes a model object.</summary>
    private object? ToClr(JsonNode? value, Type type)
    {
        switch (value)
        {
            case null:
                return null;
            case JsonObject reference when reference.ContainsKey("obj"):
                return Object(reference["obj"]);
            case JsonValue scalar:
                switch (scalar.GetValueKind())
                {
                    case JsonValueKind.True:
                        return true;
                    case JsonValueKind.False:
                        return false;
                    case JsonValueKind.String:
                        return scalar.GetValue<string>();
                    case JsonValueKind.Number:
                    {
                        Type target = Nullable.GetUnderlyingType(type) ?? type;
                        bool integral = scalar.TryGetValue(out long whole);
                        double real = scalar.GetValue<double>();
                        if (target == typeof(long)) return integral ? whole : (long)real;
                        if (target == typeof(double)) return real;
                        if (target == typeof(float)) return (float)real;
                        if (target == typeof(int)) return integral ? (int)whole : (int)real;
                        if (target == typeof(short)) return integral ? (short)whole : (short)real;
                        if (target == typeof(byte)) return integral ? (byte)whole : (byte)real;
                        if (target == typeof(decimal)) return integral ? whole : (decimal)real;
                        return integral ? (int)whole : real;
                    }
                }
                break;
        }
        throw new ScenarioFailure("cannot convert " + Json.Show(value));
    }

    // ---------------------------------------------------------------------------------------- checks

    private void Check(string path, JsonObject node)
    {
        object? actual = Resolve(path);
        if (node.ContainsKey("obj"))
        {
            object? expected = Object(node["obj"]);
            if (!ReferenceEquals(actual, expected))
            {
                throw new ScenarioFailure(path + ": expected " + Render(expected) + ", found " + Render(actual));
            }
        }
        if (node.ContainsKey("value") && !SameScalar(actual, node["value"]))
        {
            throw new ScenarioFailure(path + ": expected " + Json.Show(node["value"]) + ", found " + Render(actual));
        }
        if (node.ContainsKey("objs") || node.ContainsKey("values") || node.ContainsKey("size"))
        {
            if (actual is not IEnumerable collection || actual is string)
            {
                throw new ScenarioFailure(path + " is not a collection: " + Render(actual));
            }
            var items = collection.Cast<object?>().ToList();
            if (node.ContainsKey("size") && items.Count != Json.Int(node["size"]))
            {
                throw new ScenarioFailure(path + ": expected size " + Json.Show(node["size"]) + ", found "
                    + items.Count + " " + Render(items));
            }
            if (node["objs"] is JsonArray objs)
            {
                var expected = objs.Select(Object).ToList();
                if (!SameObjects(items, expected, Json.Bool(node["unordered"])))
                {
                    throw new ScenarioFailure(path + ": expected " + Render(expected) + ", found " + Render(items));
                }
            }
            if (node["values"] is JsonArray values)
            {
                bool same = items.Count == values.Count;
                for (int i = 0; same && i < items.Count; i++)
                {
                    same = SameScalar(items[i], values[i]);
                }
                if (!same)
                {
                    throw new ScenarioFailure(path + ": expected " + values.ToJsonString() + ", found " + Render(items));
                }
            }
        }
    }

    private static bool SameObjects(List<object?> actual, List<object?> expected, bool unordered)
    {
        if (actual.Count != expected.Count)
        {
            return false;
        }
        if (!unordered)
        {
            return actual.Zip(expected).All(pair => ReferenceEquals(pair.First, pair.Second));
        }
        var remaining = new List<object?>(expected);
        foreach (object? item in actual)
        {
            int found = remaining.FindIndex(candidate => ReferenceEquals(candidate, item));
            if (found < 0)
            {
                return false;
            }
            remaining.RemoveAt(found);
        }
        return true;
    }

    private static bool SameScalar(object? actual, JsonNode? expected)
    {
        if (expected is null || expected.GetValueKind() == JsonValueKind.Null)
        {
            return actual is null;
        }
        if (actual is null)
        {
            return false;
        }
        switch (expected.GetValueKind())
        {
            case JsonValueKind.Number:
            {
                if (actual is not (int or long or short or byte or sbyte or uint or ulong or ushort or double or float or decimal))
                {
                    return false;
                }
                bool integral = expected.AsValue().TryGetValue(out long whole);
                return integral && actual is not (double or float)
                    ? Convert.ToInt64(actual, CultureInfo.InvariantCulture) == whole
                    : Convert.ToDouble(actual, CultureInfo.InvariantCulture) == expected.GetValue<double>();
            }
            case JsonValueKind.True:
            case JsonValueKind.False:
                return actual is bool flag && flag == (expected.GetValueKind() == JsonValueKind.True);
            case JsonValueKind.String:
                return actual is string text && text == expected.GetValue<string>();
            case JsonValueKind.Array:
            {
                if (actual is not IEnumerable collection || actual is string)
                {
                    return false;
                }
                var items = collection.Cast<object?>().ToList();
                var wanted = expected.AsArray();
                return items.Count == wanted.Count && items.Zip(wanted).All(pair => SameScalar(pair.First, pair.Second));
            }
            default:
                return false;
        }
    }

    private void Pairs(JsonArray paths, Action<object?, object?> check)
    {
        object? left = Resolve(Json.Text(paths[0]));
        for (int i = 1; i < paths.Count; i++)
        {
            object? right = Resolve(Json.Text(paths[i]));
            try
            {
                check(left, right);
            }
            catch (ScenarioFailure failure)
            {
                throw new ScenarioFailure(Json.Text(paths[0]) + " vs " + Json.Text(paths[i]) + ": " + failure.Message);
            }
        }
    }

    private void CheckAnnotations(string path, JsonArray expected)
    {
        IReadOnlyList<ModelAnnotation> annotations;
        if (path.Contains('.'))
        {
            var (owner, name, _) = Owner(path);
            annotations = Property(owner, name).Annotations;
        }
        else
        {
            annotations = Reflect(Resolve(path)).Annotations;
        }
        var actual = annotations.Where(annotation => !annotation.Key.StartsWith("vmf:", StringComparison.Ordinal))
            .Select(annotation => annotation.Key + "=" + annotation.Value).ToList();
        var wanted = expected.Select(pair => Json.Text(pair![0]) + "=" + Json.Text(pair[1])).ToList();
        if (!actual.SequenceEqual(wanted))
        {
            throw new ScenarioFailure(path + ": expected annotations " + List(wanted) + ", found " + List(actual));
        }
    }

    // ---------------------------------------------------------------------------------------- events

    private void Listen(string path, string name, string scope)
    {
        if (!events.TryGetValue(name, out var received))
        {
            events[name] = received = [];
        }
        var subscription = Changes(Resolve(path)).Listen(ToEvent, ToEvent, received,
            scope == "local" ? ModelChangeScope.Local : ModelChangeScope.Recursive);
        subscriptions[name] = subscription;
    }

    private Event ToEvent(ModelChange change) => change switch
    {
        ModelCollectionChange collection => CollectionEvent(change.Source, change.PropertyName, collection.Added,
            collection.Removed),
        _ when change.Kind == ModelChangeKind.Property => new Event(step, change.Source, change.PropertyName, "set",
            change.OldValue, change.NewValue, [], []),
        _ => new Event(step, change.Source, change.PropertyName, "other", null, null, [], []),
    };

    private Event ToEvent(ReadOnlyModelChange change) => change.Kind == ModelChangeKind.Property
        ? new Event(step, change.Source, change.PropertyName, "set", change.OldValue, change.NewValue, [], [])
        : CollectionEvent(change.Source, change.PropertyName, change.Added, change.Removed);

    /// <summary>Like Java's list events: a slot replacement carries both sides, reported with the added side.</summary>
    private Event CollectionEvent(object source, string property, IReadOnlyList<ModelCollectionEntry> added,
        IReadOnlyList<ModelCollectionEntry> removed)
    {
        string kind = added.Count > 0 && removed.Count > 0 ? "replace" : added.Count > 0 ? "add" : "remove";
        var entries = added.Count > 0 ? added : removed;
        return new Event(step, source, property, kind, null, null,
            entries.Select(entry => entry.Value).ToList(),
            entries.Where(entry => entry.Index.HasValue).Select(entry => entry.Index!.Value).ToList());
    }

    /// <summary>
    /// Normalizes the events of one step: consecutive list events that differ only in their elements are merged
    /// (bulk edits), and a chain of set events on the same property (a -> b, b -> c) becomes one event (a -> c).
    /// </summary>
    private static List<Event> Merged(List<Event> received)
    {
        var result = new List<Event>();
        foreach (var current in received)
        {
            Event? last = result.Count == 0 ? null : result[^1];
            bool sameTarget = last is not null && last.Step == current.Step && ReferenceEquals(last.Source, current.Source)
                && last.Property == current.Property && last.Kind == current.Kind;
            if (sameTarget && current.Kind is "add" or "remove")
            {
                result[^1] = last! with
                {
                    OldValue = null,
                    NewValue = null,
                    Elements = [.. last.Elements, .. current.Elements],
                    Indices = [.. last.Indices, .. current.Indices],
                };
            }
            else if (sameTarget && current.Kind == "set" && Chained(last!.NewValue, current.OldValue))
            {
                result[^1] = last with { NewValue = current.NewValue, Elements = [], Indices = [] };
            }
            else
            {
                result.Add(current);
            }
        }
        return result;
    }

    private static bool Chained(object? previousNewValue, object? nextOldValue) =>
        IsModelObject(previousNewValue) || IsModelObject(nextOldValue)
            ? ReferenceEquals(previousNewValue, nextOldValue)
            : Equals(previousNewValue, nextOldValue);

    private void CheckEvents(string name, JsonObject node)
    {
        if (!events.TryGetValue(name, out var buffer))
        {
            events[name] = buffer = [];
        }
        var received = Merged([.. buffer]);
        buffer.Clear();
        if (node["ignore"] is JsonArray ignore)
        {
            received.RemoveAll(current => ignore.Any(pattern => Matches(pattern!.AsObject(), current)));
        }
        if (node.ContainsKey("count"))
        {
            if (received.Count != Json.Int(node["count"]))
            {
                throw new ScenarioFailure(name + ": expected " + Json.Show(node["count"]) + " events, received "
                    + Render(received));
            }
            return;
        }
        var expected = node["are"]!.AsArray();
        if (expected.Count != received.Count)
        {
            throw new ScenarioFailure(name + ": expected " + expected.ToJsonString() + ", received " + Render(received));
        }
        var remaining = new List<Event>(received);
        bool anyOrder = Json.Bool(node["anyOrder"]);
        foreach (var wanted in expected)
        {
            int match = -1;
            for (int j = 0; j < (anyOrder ? remaining.Count : 1); j++)
            {
                if (Matches(wanted!.AsObject(), remaining[j]))
                {
                    match = j;
                    break;
                }
            }
            if (match < 0)
            {
                throw new ScenarioFailure(name + ": expected " + expected.ToJsonString() + ", received " + Render(received));
            }
            remaining.RemoveAt(match);
        }
    }

    private bool Matches(JsonObject wanted, Event current)
    {
        if (wanted.ContainsKey("on") && !ReferenceEquals(Resolve(Json.Text(wanted["on"])), current.Source)) return false;
        if (wanted.ContainsKey("prop") && Json.Text(wanted["prop"]) != current.Property) return false;
        if (wanted.ContainsKey("kind") && Json.Text(wanted["kind"]) != current.Kind) return false;
        if (wanted.ContainsKey("old") && !SameScalar(current.OldValue, wanted["old"])) return false;
        if (wanted.ContainsKey("new") && !SameScalar(current.NewValue, wanted["new"])) return false;
        if (wanted.ContainsKey("oldObj") && !ReferenceEquals(Object(wanted["oldObj"]), current.OldValue)) return false;
        if (wanted.ContainsKey("newObj") && !ReferenceEquals(Object(wanted["newObj"]), current.NewValue)) return false;
        if (wanted.ContainsKey("values") && !SameScalar(current.Elements, wanted["values"])) return false;
        if (wanted["objs"] is JsonArray objs && !SameObjects(current.Elements, objs.Select(Object).ToList(), false))
        {
            return false;
        }
        if (wanted["index"] is JsonArray index)
        {
            return index.Select(Json.Int).SequenceEqual(current.Indices);
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------- history

    private void UndoAll(string label)
    {
        ModelChanges changes = Changes(Resolve(label)).Mutable;
        changes.Stop();
        var recorded = changes.History.ToList();
        for (int i = recorded.Count - 1; i >= 0; i--)
        {
            recorded[i].Undo();
        }
        undone[label] = recorded;
    }

    // ---------------------------------------------------------------------------------------- rendering

    private static string List(IEnumerable<string> items) => "[" + string.Join(", ", items) + "]";

    private string Render(object? value)
    {
        switch (value)
        {
            case null:
                return "null";
            case Event e:
                return "{" + Render(e.Source) + "." + e.Property + " " + e.Kind
                    + (e.Kind == "set" ? " " + Render(e.OldValue) + "->" + Render(e.NewValue)
                        : " " + Render(e.Elements) + "@[" + string.Join(", ", e.Indices) + "]") + "}";
            case string text:
                return "\"" + text + "\"";
            case IEnumerable items:
                return "[" + string.Join(", ", items.Cast<object?>().Select(Render)) + "]";
        }
        if (IsModelObject(value))
        {
            foreach (string label in labelOrder)
            {
                if (ReferenceEquals(labels[label], value))
                {
                    return label;
                }
            }
            return "<" + value.GetType().Name + ">";
        }
        return Scalar(value);
    }

    private static string Scalar(object? value) => value switch
    {
        null => "null",
        bool flag => flag ? "true" : "false",
        _ => Convert.ToString(value, CultureInfo.InvariantCulture) ?? "",
    };

    /// <summary>A textual dump of every object reachable from a label, used to prove rejected edits changed nothing.</summary>
    private string Fingerprint()
    {
        var ids = new Dictionary<object, int>(ReferenceEqualityComparer.Instance);
        var pending = new List<object>();
        var text = new StringBuilder();
        foreach (string label in labelOrder)
        {
            text.Append(label).Append('=').Append(Reference(labels[label], ids, pending)).Append(' ');
        }
        for (int i = 0; i < pending.Count; i++)
        {
            object current = pending[i];
            text.Append('#').Append(ids[current]).Append('{');
            foreach (ModelProperty property in Reflect(current).Properties)
            {
                object? value = property.Get();
                text.Append(property.Name).Append('=');
                if (value is IEnumerable items && value is not string)
                {
                    text.Append('[');
                    foreach (object? element in items)
                    {
                        text.Append(Reference(element, ids, pending)).Append(',');
                    }
                    text.Append(']');
                }
                else
                {
                    text.Append(Reference(value, ids, pending));
                }
                text.Append(';');
            }
            text.Append("} ");
        }
        return text.ToString();
    }

    private static string Reference(object? value, Dictionary<object, int> ids, List<object> pending)
    {
        if (IsModelObject(value))
        {
            if (!ids.TryGetValue(value!, out int id))
            {
                id = ids.Count;
                ids[value!] = id;
                pending.Add(value!);
            }
            return "#" + id;
        }
        return Scalar(value);
    }
}

/// <summary>
/// Edits a generated model list through its public C# API (Add, Insert, AddRange, InsertRange, RemoveAt, Clear and the
/// indexer). A read-only projection has none of these, so an edit through it is rejected like Java's unmodifiable lists.
/// </summary>
internal static class ModelLists
{
    internal static void Add(object list, object? item) => Invoke(list, "Add", 1, item);

    internal static void Insert(object list, int index, object? item) => Invoke(list, "Insert", 2, index, item);

    internal static void RemoveAt(object list, int index) => Invoke(list, "RemoveAt", 1, index);

    internal static void Clear(object list) => Invoke(list, "Clear", 0);

    internal static void Set(object list, int index, object? item)
    {
        PropertyInfo indexer = list.GetType().GetProperties(BindingFlags.Public | BindingFlags.Instance)
            .FirstOrDefault(property => property.GetIndexParameters() is [{ ParameterType: var key }] && key == typeof(int)
                && property.SetMethod is { IsPublic: true })
            ?? throw new InvalidOperationException(list.GetType().Name + " has no writable indexer");
        indexer.SetValue(list, item, [index]);
    }

    internal static void AddRange(object list, int index, List<object?> items)
    {
        Type element = ElementType(list);
        var typed = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(element))!;
        foreach (object? item in items)
        {
            typed.Add(item);
        }
        if (index < 0)
        {
            Invoke(list, "AddRange", 1, typed);
        }
        else
        {
            Invoke(list, "InsertRange", 2, index, typed);
        }
    }

    private static Type ElementType(object list) =>
        list.GetType().GetInterfaces()
            .FirstOrDefault(i => i.IsGenericType && i.GetGenericTypeDefinition() == typeof(IList<>))
            ?.GetGenericArguments()[0]
        ?? throw new InvalidOperationException(list.GetType().Name + " is not an editable list");

    private static void Invoke(object list, string name, int arity, params object?[] arguments)
    {
        MethodInfo method = list.GetType().GetMethods(BindingFlags.Public | BindingFlags.Instance)
            .Where(candidate => candidate.Name == name && candidate.GetParameters().Length == arity)
            .OrderBy(candidate => candidate.GetParameters().Any(p => p.ParameterType == typeof(object)) ? 1 : 0)
            .FirstOrDefault()
            ?? throw new InvalidOperationException(list.GetType().Name + " does not support " + name);
        method.Invoke(list, arguments);
    }
}
