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

Use type parameters as scalar properties, list or set elements (`List<T>`, `T[]`, or `Set<T>`), or managed map keys and values (`VMap<K,V>`). A concrete argument can be a named external value type or an immutable model declaration. An immutable model keeps the same Java type through public and read-only access. Scalar sets, ordered unique scalar lists, and managed map slots accept `String` or a boxed primitive. VMF validates the concrete binding of each inherited property during generation. Define containment, references, and synchronization on properties with a named model target.

Use the named concrete type as a property target and when creating, registering, or decoding instances. A persisted `Temperatures` object has a concrete identity that resolves its arguments even when every property is empty. VMF reflection exposes the resolved property descriptors and parameterized supertypes through `Type.getDescriptor()` and `Type.superTypes()`. An open supertype parameter carries its declaring type and parameter name.
