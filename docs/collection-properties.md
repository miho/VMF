# Collections in VMF models

Choose the Java getter that describes what callers do with the property.

| Declaration in a model interface | Generated API | Meaning |
| --- | --- | --- |
| `List<T> getSamples()` or `T[] getSamples()` | `VList<T>` | Ordered sequence. Repeated values are allowed. |
| `@Multiplicity(kind = ORDERED_SET) List<String> getSteps()` | `VList<String>` | Ordered, unique scalar values. Supports indexed moves. |
| `Set<String> getTags()` | `VSet<String>` | Unordered membership. Adding an existing tag returns false and emits no change. |
| `VMap<Integer, Double> getReadings()` | `VMap<Integer,Double>` | Scalar keys and values. Each key has at most one mapping. |
| `@Contains @Keyed(by = "sku") Item[] getItems()` | `VList<Item>` and `findItemsBySku(String)` | An owned relationship with typed lookup. Keys can be scalar values or model references. |

The example uses `import static eu.mihosoft.vmf.core.Multiplicity.Kind.ORDERED_SET;`. Sets, checked scalar lists and scalar map keys accept non-null strings and boxed Java primitives. A scalar map value may be null. Unordered sets compare by membership. A set change contains immutable sets of added and removed members.

On an ordered set, `add(value)` returns false for an existing member. `addAll(values)` and `addAll(index, values)` insert only new members, in encounter order, and check capacity after filtering. An indexed single addition rejects an existing member. Setting a slot to a member already at another index also throws. Exact replacements through builders or reflection require distinct input.

## Translate a UML association

A UML association end with no multiplicity annotation defaults to 1..1. A nullable VMF scalar getter defaults to 0..1, so use `@Required` or `@Multiplicity(lower = 1)` to preserve the UML requirement.

A workshop owns one to four stations. Each station can navigate back to its workshop.

```java
interface Workshop {
    @Contains(opposite = "workshop")
    @Multiplicity(lower = 1, upper = 4)
    Station[] getStations();
}

interface Station {
    @Container(opposite = "stations")
    Workshop getWorkshop();
}
```

This Java declaration chooses a sequence for stations. UML's default for a many-valued end is **unordered and unique**. Ecore defaults to **ordered and unique**. Select the collection semantics from the source diagram. VMF currently supports unique collections of scalar values. A unique end containing model objects and an unordered bag are reported as unsupported at generation time. [UML §7.5.3.2](https://www.omg.org/spec/UML/2.5.1/PDF) · [Ecore typed elements](https://download.eclipse.org/modeling/emf/emf/javadoc/2.10.0/org/eclipse/emf/ecore/ETypedElement.html) · [Unique reference ends roadmap](https://github.com/miho/VMF/issues/122)

EMF also requires a many-valued containment or bidirectional reference to be unique. When translating those Ecore features, preserve that requirement explicitly. Unannotated VMF relationship lists retain their existing sequence behavior. [Ecore reference validator](https://github.com/eclipse-emf/org.eclipse.emf/blob/b940729ba08b2499b08ff505d9f038e2e33cdc73/plugins/org.eclipse.emf.ecore/src/org/eclipse/emf/ecore/util/EcoreValidator.java#L2641-L2669)

`workshop.vmf().content().validate()` checks the workshop and its contained descendants. Each violation identifies its object, property, and current count. Call it when a graph is ready to publish. Building and reparenting can pass through incomplete states. Bounded managed scalar lists, sets, and maps also check their upper bound on mutation. Reflection exposes each property's lower and upper bounds, semantic ordering, and uniqueness through `getMultiplicity()`. An upper bound of `-1` means unlimited.

## Choose a map representation

Use a direct `VMap` for scalar data. For a qualified association such as `Catalog[sku] → Item`, declare a keyed list. The generated lookup returns an `Optional`. The list owns the item once, its key may be edited, and duplicates are checked before mutation. For a model-object key, the lookup compares object identity and recognizes a read-only view of the same object.

When both sides are model objects, give the relationship its own entry type. Put the key reference and the value reference or containment on the entry. A many-valued value is an ordinary typed list on that entry. Its ownership, traversal, changes, and serialization then follow the same rules as elsewhere in the model. [Keyed entry examples](typed-model-properties.md) · [Journal representation](change-journal.md#typed-keys-unordered-sets-and-keyed-model-entries)

## Inherit a collection

A generic `@InterfaceOnly` declaration can use a parameter as a scalar set or list element, or as either argument of a scalar map. The named subclass binds the parameter. VMF checks the resulting property during generation, and the generated builder and reflection expose its concrete type. [Generic model interfaces](generic-model-interfaces.md)

Immutable model type arguments are supported for ordinary scalar and list properties. Mutable model type arguments require a read-only projection that carries through the full inheritance chain. [Generic model projection roadmap](https://github.com/miho/VMF/issues/123)
