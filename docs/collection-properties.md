# Collections in VMF models

Choose the Java getter that describes what callers do with the property.

| Declaration in a model interface | Generated API | Meaning |
| --- | --- | --- |
| `List<T> getSamples()` or `T[] getSamples()` | `VList<T>` | Ordered sequence. Repeated values are allowed. |
| `@Multiplicity(kind = ORDERED_SET) List<String> getSteps()` | `VList<String>` | Ordered, unique scalar values. Supports indexed moves. |
| `Set<String> getTags()` | `VSet<String>` | Unordered membership. Adding an existing tag returns false and emits no change. |
| `Set<Station> getFavorites()` | `RefSet<Station>` | Unordered membership by model-object identity. |
| `@Multiplicity(kind = ORDERED_SET) Station[] getStations()` | `OrderedRefSet<Station>` | Unique references with indexed access and moves. |
| `VMap<Integer, Double> getReadings()` | `VMap<Integer,Double>` | Scalar keys and values. Each key has at most one mapping. |
| `@Contains @Keyed(by = "sku") Item[] getItems()` | `VList<Item>` and `findItemsBySku(String)` | An owned relationship with typed lookup. Keys can be scalar values or model references. |

The examples use `import static eu.mihosoft.vmf.core.Multiplicity.Kind.ORDERED_SET;`. Scalar sets, checked scalar lists and scalar map keys accept non-null strings and boxed Java primitives. A scalar map value may be null. Unordered sets compare by membership.

On an ordered scalar set, `add(value)` returns false for an existing member. `addAll(values)` and `addAll(index, values)` insert only new members, in encounter order, and check capacity after filtering. An indexed single addition rejects an existing member. Setting a slot to a member already at another index also throws. Exact replacements through builders or reflection require distinct input.

## Translate a UML association

A UML association end with no multiplicity annotation defaults to 1..1. A nullable VMF scalar getter defaults to 0..1, so use `@Required` or `@Multiplicity(lower = 1)` to preserve the UML requirement.

A workshop owns one to four stations. Each station can navigate back to its workshop.

```java
interface Workshop {
    @Contains(opposite = "workshop")
    @Multiplicity(lower = 1, upper = 4, kind = ORDERED_SET)
    Station[] getStations();
}

interface Station {
    @Container(opposite = "stations")
    Workshop getWorkshop();
}
```

Two different stations named “Inspection” can belong to this workshop, even when their contents compare equal. Adding the same station twice returns `false` on the second call. `remove(second)` detaches exactly that station. `move(1, 0)` reorders the stations in one edit and preserves their workshop. A read-only workshop exposes `OrderedRefSet<ReadOnlyStation>`. A station and its read-only view identify the same member for lookup.

Membership lookup scans the relationship. To load many targets, use `addAll(targets)` so VMF checks existing and incoming identities in one pass. Deep copies preserve aliases between relationships. A shallow copy can share targets on a non-owning reference end when its opposite permits sharing.

For the UML default of an **unordered, unique** association end, declare `Set<Station>`. Ecore defaults to **ordered and unique**, as in the workshop example. VMF rejects unordered bags at generation time. [UML §7.5.3.2](https://www.omg.org/spec/UML/2.5.1/PDF) · [Ecore typed elements](https://download.eclipse.org/modeling/emf/emf/javadoc/2.10.0/org/eclipse/emf/ecore/ETypedElement.html)

EMF also requires a many-valued containment or bidirectional reference to be unique. When translating those Ecore features, preserve that requirement explicitly. Unannotated VMF relationship lists retain their existing sequence behavior. [Ecore reference validator](https://github.com/eclipse-emf/org.eclipse.emf/blob/b940729ba08b2499b08ff505d9f038e2e33cdc73/plugins/org.eclipse.emf.ecore/src/org/eclipse/emf/ecore/util/EcoreValidator.java#L2641-L2669)

`workshop.vmf().content().validate()` checks the workshop and its contained descendants. Zero or five stations produce a violation with the object, property and actual count. Building and reparenting can pass through intermediate sizes. Unique reference collections reject null on insertion. Legacy relationship lists can hold null slots, which count as zero targets. Bounded scalar lists, sets, and maps also check their upper bound on mutation. Reflection exposes lower and upper bounds, ordering, and uniqueness through `getMultiplicity()`. An upper bound of `-1` means unlimited.

Listen to the owning model's changes and use `change.referenceSetChange()` for membership edits. Its `added()` and `removed()` snapshots preserve distinct objects with equal content. An ordered edit also reports its indices. [Persistence and replay](change-journal.md)

Jackson writes owned targets inline. The default mapper also reads and writes inline immutable values, including an array of objects for a unique immutable relationship. Each inline occurrence creates a separate instance. Use `withReferences(true)` when two ends must point to the same target after loading. A mutable target belongs in the saved containment tree. A shared immutable target can be defined inline and referenced elsewhere by its document ID. [Jackson reference format](../jackson/README.md)

## Choose a map representation

Use a direct `VMap` for scalar data. For a qualified association such as `Catalog[sku] → Item`, declare a keyed list. The generated lookup returns an `Optional`. The list owns the item once, its key may be edited, and duplicates are checked before mutation. For a model-object key, the lookup compares object identity and recognizes a read-only view of the same object.

When both sides are model objects, give the relationship its own entry type. Put the key reference and the value reference or containment on the entry. A many-valued value is an ordinary typed list on that entry. Its ownership, traversal, changes, and serialization then follow the same rules as elsewhere in the model. [Keyed entry examples](typed-model-properties.md) · [Journal representation](change-journal.md#typed-keys-unordered-sets-and-keyed-model-entries)

## Inherit a collection

A generic `@InterfaceOnly` declaration can use a parameter as a scalar set or list element, or as either argument of a scalar map. The named subclass binds the parameter. VMF checks the resulting property during generation, and the generated builder and reflection expose its concrete type. [Generic model interfaces](generic-model-interfaces.md)

To parameterize a model relationship, give its parameter a named model bound, such as `T extends Product`. A subtype that binds `T` to `Instrument` then exposes `Instrument` on its writable collection and `ReadOnlyInstrument` on its read-only collection. The same ordering and identity rules apply to direct and inherited declarations. [Generic model relationships](generic-model-interfaces.md#inherit-a-model-relationship)
