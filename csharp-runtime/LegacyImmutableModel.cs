using System.Reflection;
using System.Runtime.CompilerServices;

namespace Vmf.Runtime;

/// <summary>Recognizes immutable model contracts generated before the native marker was emitted.</summary>
internal static class LegacyImmutableModel
{
    private const BindingFlags DeclaredPublic = BindingFlags.Public | BindingFlags.DeclaredOnly;
    private static readonly ConditionalWeakTable<Type, Classification> Types = new();

    internal static bool IsImmutable(object? value) => value is IImmutableModel
        || value is not null && Types.GetValue(value.GetType(), static type => new(HasLegacyContract(type))).Immutable;

    private static bool HasLegacyContract(Type type)
    {
        // A derived immutable value may inherit its read-only change stream from its generated root.
        for (Type? declaration = type; declaration is not null; declaration = declaration.BaseType)
        {
            var changes = declaration.GetMember(nameof(IReadOnlyModelView.VmfChanges),
                MemberTypes.Property, DeclaredPublic | BindingFlags.Instance).OfType<PropertyInfo>();
            if (!changes.Any(property => property.PropertyType == typeof(ReadOnlyModelChanges)
                    && property.GetMethod is not null && !property.CanWrite && property.GetIndexParameters().Length == 0))
            {
                continue;
            }

            Type? builder = declaration.GetNestedType("Builder", DeclaredPublic);
            if (builder is null || !builder.GetInterfaces().Any(contract =>
                    contract.IsGenericType && contract.GetGenericTypeDefinition() == typeof(IModelBuilder<>)
                        && contract.GenericTypeArguments[0] == declaration))
            {
                continue;
            }

            if (HasMethod(declaration, "AsReadOnly", declaration, BindingFlags.Instance)
                && HasMethod(declaration, "NewBuilder", builder, BindingFlags.Static))
            {
                return true;
            }
        }
        return false;
    }

    private static bool HasMethod(Type declaration, string name, Type result, BindingFlags scope) =>
        declaration.GetMember(name, MemberTypes.Method, DeclaredPublic | scope).OfType<MethodInfo>()
            .Any(method => !method.IsGenericMethod && method.ReturnType == result && method.GetParameters().Length == 0);

    private sealed record Classification(bool Immutable);
}
