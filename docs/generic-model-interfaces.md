# Generic model interfaces

A generic `@InterfaceOnly` declaration describes properties shared by several named model types. A concrete subtype supplies the arguments that VMF uses for its generated Java API, read-only views, builders, reflection, persistence, and journal replay.

```java
package demo.telemetry.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.InterfaceOnly;
import java.util.List;

@InterfaceOnly
interface Series<T> {
    T getLatest();
    List<T> getSamples();
    VMap<String, T> getNamedSamples();
}

interface Temperatures extends Series<Double> {}
interface Labels extends Series<String> {}
```

A caller can use the concrete builder and the common interface together.

```java
Temperatures temperatures = Temperatures.newBuilder()
        .withLatest(20.5)
        .withSamples(19.75, 20.5)
        .withNamedSamples(Map.of("indoor", 20.5))
        .build();

Series<Double> series = temperatures;
series.setLatest(21.0);
ReadOnlySeries<Double> view = temperatures.asReadOnly();
```

The declaration supports multiple type parameters, named scalar upper bounds, and forwarding parameters through intermediate interfaces. For example, an `@InterfaceOnly NumericSeries<N extends Number> extends Series<N>` can supply `N` to the base interface before `Temperatures` binds it to `Double`.

Use type parameters as scalar properties, list or set elements (`List<T>`, `T[]`, or `Set<T>`), or managed map keys and values (`VMap<K,V>`). A concrete argument can be a named external value type or an immutable model declaration. An immutable model keeps the same Java type through public and read-only access. Scalar sets, ordered unique scalar lists, and managed map slots accept `String` or a boxed primitive. VMF validates the concrete binding of each inherited property during generation.

Use the named concrete type as a property target and when creating, registering, or decoding instances. A persisted `Temperatures` object has a concrete identity that resolves its arguments even when every property is empty. VMF reflection exposes the resolved property descriptors and parameterized supertypes through `Type.getDescriptor()` and `Type.superTypes()`. An open supertype parameter carries its declaring type and parameter name.

## Inherit a model relationship

Give a relationship parameter a model type as its upper bound. This tells VMF which read-only view its targets expose and how to compare members of a unique collection.

```java
@InterfaceOnly
interface Product { String getSku(); }

@InterfaceOnly
interface Shelf<T extends Product> {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    T[] getProducts();
    T getFeatured();
}

interface Instrument extends Product {}
interface Catalog extends Shelf<Instrument> {}
```

`Catalog.getProducts()` returns an `OrderedRefSet<Instrument>`. `Catalog.asReadOnly().getProducts()` returns an `OrderedRefSet<ReadOnlyInstrument>`. The binding also applies to `getFeatured()`, inherited keyed lookups, and their read-only results. A caller holding the concrete catalog can use either interface directly.

```java
Shelf<Instrument> shelf = catalog;
ReadOnlyShelf<ReadOnlyInstrument> view = catalog.asReadOnly();
```

A caller holding only `Shelf<T>` can obtain a `ReadOnlyShelf<? extends ReadOnlyProduct>`. Java can express the read-only bound at that point. The more specific `ReadOnlyInstrument` is known on the concrete catalog. An unbounded value parameter keeps the same type in both views, as in `Series<Double>` above.

For an unordered unique relationship, declare `Set<T>` with the same model bound. Use `@Keyed` on a contained collection to look up model entries by a scalar key or by model-object identity. [Collection declarations](collection-properties.md)

Use a named model interface when a bound needs to combine several model APIs. For example, define `TrackedProduct extends Product, Tracked` and use `T extends TrackedProduct`. This gives the combined bound its own read-only type. `@Contains`, `@Container`, and `@Refers` support model-bounded targets. VMF rejects `@SyncWith` on a property whose target is a type parameter.

### Read an existing JSON document

Choose the concrete model class when reading a document. The binding supplies the type of each nested item, including when the input contains an empty array. An existing field name can be mapped on the generic declaration with `@Annotation(key = "vmf:jackson:rename", value = "items")` on `getProducts()`.

```java
var mode = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(mode));
Catalog catalog = mapper.readValue("{\"items\":[{\"sku\":\"G-27\"}]}", Catalog.class);
```

Use the default mapper for inline values. Enable `withReferences(true)` to preserve links such as `featured` pointing at a specific product, or two products sharing the same immutable label. A stored journal identifies the targets of later edits through its binding. [Snapshot formats](../jackson/README.md) · [Change journals](change-journal.md)
