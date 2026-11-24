# Managed maps and generic model interfaces

These features require the matching VMF 0.4.1-SNAPSHOT generator, runtime, and build plugin from this branch. The runtime depends on VCollections 0.3.7-SNAPSHOT with observable map views. Use the matching VMF Jackson module for model persistence. For local development, publish the VCollections dependency before building VMF, then regenerate the model sources. [VMF build instructions](../README.md#building-vmf-core-runtime-and-plugin)

## Declare a managed map

In a model definition, declare a property using `eu.mihosoft.vcollections.VMap`. Every instance owns its map. The getter returns the same map throughout the lifetime of the instance, so listeners and read-only views continue to follow the property across edits.

```java
package example.measurements.vmfmodel;

import eu.mihosoft.vcollections.VMap;

interface Device {
    VMap<String, Double> getReadings();
}
```

The generated builder accepts an ordinary Java map and copies its entries. Edit an individual reading through the generated getter.

```java
var device = Device.newBuilder().withReadings(Map.of("pressure", 101.3)).build();
var readings = device.getReadings();
readings.put("pressure", 101.8);
readings.put("humidity", null);

var view = device.asReadOnly().getReadings();
readings.remove("pressure");
assert view.containsKey("humidity");
assert view.get("humidity") == null;
```

Keys must be non-null strings. Values may be strings or boxed Java primitives (`Boolean`, `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`, or `Character`). Entries may hold null. Use `containsKey` to distinguish a present null from an absent key. Model references and containment belong on separately declared relationship properties.

A managed map starts empty unless its definition supplies a map through `@DefaultValue`. Both deep and shallow copies of mutable models own independent entries. Read-only models expose a live, unmodifiable view. An immutable model can initialize its map through the builder and exposes an unmodifiable map. Ordinary `java.util.Map` properties continue to use VMF's external-value handling. Choose `VMap` on the model getter to opt into entry observation.

### Observe, reflect, and persist

Subscribe through the model change API to receive a `Change` with type `MAP`. For a replacement, the old mapping is in `removed().entries()` and the new mapping is in `added().entries()`. Bulk edits can describe multiple keys in the same event. Release the subscription when observation ends.

```java
var subscription = device.vmf().changes().addListener(change ->
    change.mapChange().ifPresent(event ->
        System.out.println(event.removed().entries() + " -> " + event.added().entries())));
readings.put("pressure", 102.0);
subscription.unsubscribe();
```

Reflection reports both arguments in declaration order. The same descriptor is available from detached property metadata. Setting the property reflectively copies validated entries into the owned map.

```java
var property = device.vmf().reflect().propertyByName("readings").orElseThrow();
var type = property.getType();
assert type.isMapType();
assert type.getDescriptor().getArguments().get(1).getName().equals("java.lang.Double");
property.set(Map.of("pressure", 99.4));
property.unset();
```

VMF Jackson represents the property as a JSON object with string keys. The change journal records map entries by key and distinguishes null from absence during replay. [Journal format and replay](change-journal.md#version-3-managed-maps)

## Bind a generic map value

A reusable interface can declare `VMap<String,T>`. A concrete subtype binds `T` and supplies the value type for its builder, runtime metadata, serializer, and journal. The generic model guide shows a single declaration that uses the same parameter in a scalar, a list, and a map. [Generic model interfaces](generic-model-interfaces.md)

## How EMF and NMF represent the same data

The Ecore model already contains a string-to-string map, `EAnnotation.details`. It provides a shared example for Eclipse EMF and the .NET Modeling Framework (NMF). VMF's equivalent is a `VMap<String,String>` model property.

| | VMF managed map | Eclipse EMF | NMF EcoreInterop |
| --- | --- | --- | --- |
| Generated API | `VMap<String,String>`, with ordinary Java map operations | `EMap<String,String>`, an ordered list of entries with keyed operations and a Java `Map` view | `IOrderedSetExpression<IEStringToStringMapEntry>`, a collection of entry objects |
| Reusing a key | `put` replaces its mapping | `put` updates the existing entry | Two distinct entries can have the same `Key`. Updating a mapping by key requires a selection and duplicate-key policy |
| Observation | One map-property change exposes before and after mappings for affected keys | Insertion notifies the owner's containment feature. Updating an existing mapping notifies the entry's value feature | The collection notifies on entry addition or removal. Each entry exposes key and value change events |
| Persistence | JSON/YAML objects, explicit entries in XML, keyed operations in portable journals | Ecore XMI stores the annotation's contained key/value entries | The NMF repository stores the annotation's contained entries in XMI, including order and duplicate key attributes |

In a local probe using EMF Ecore 2.43.0, putting `sensor=v1` and then `sensor=v3` produced an owner `ADD` and an entry `SET`. XMI contained one `details` element and reloaded the value `v3`. An Ecore map entry can declare its key and value as attributes or references, which also supports model-object types. [EMF map modeling guide](https://eclipse.dev/emf/markdown/?file=FAQ.md) · [EMap API](https://download.eclipse.org/modeling/emf/emf/javadoc/2.7.0/org/eclipse/emf/common/util/EMap.html) · [EMF annotation implementation](https://github.com/eclipse-emf/org.eclipse.emf/blob/b940729ba08b2499b08ff505d9f038e2e33cdc73/plugins/org.eclipse.emf.ecore/src/org/eclipse/emf/ecore/impl/EAnnotationImpl.java)

With NMF-EcoreInterop 2.0.461 on .NET 10, adding an entry, editing its value, then adding a second entry with the same key produced two collection additions and one entry value change. Saving and reloading XMI preserved both entries. This describes NMF's generated Ecore model. Its Expressions library also provides a `ChangeAwareDictionary<TKey,TValue>` for observing lookups by key. [NMF annotation API](https://github.com/NMFCode/NMF/blob/b5b45f56ec31860c60e0dcfbffc15844873f7e76/Interop/EcoreInterop/Model/IEAnnotation.cs) · [NMF entry notifications](https://github.com/NMFCode/NMF/blob/b5b45f56ec31860c60e0dcfbffc15844873f7e76/Interop/EcoreInterop/Model/EStringToStringMapEntry.cs) · [NMF change-aware dictionary](https://github.com/NMFCode/NMF/blob/b5b45f56ec31860c60e0dcfbffc15844873f7e76/Expressions/Expressions.Utilities/ChangeAwareDictionary.cs)

For a future language binding, preserve string-key uniqueness, present-null versus missing, and observable key replacement. These rules give a C# consumer the same data semantics as a Java consumer. An importer reading an entry collection should detect duplicate keys and surface the conflict before conversion. If an application needs separate entry identity, duplicate keys, or relationships on an entry, a contained collection of explicit entry model objects expresses those requirements today.

## Should VMF support sets?

Yes. For example, a device's capability names are unique, and two devices can advertise the same capabilities in different orders. An unordered set expresses that requirement directly. The proposed next step after managed maps and bound generic interfaces is a managed set of immutable scalar values. Set properties are currently a design proposal. [Set design issue](https://github.com/miho/VMF/issues/120)

| Model | How it expresses uniqueness and order | What a caller observes |
| --- | --- | --- |
| Eclipse EMF | Ecore has independent `unique` and `ordered` flags. Many-valued Java features expose an `EList`. | In a dynamic Ecore 2.43.0 model, adding an equal string twice produced one addition. Even with `ordered=false`, the unique list accepted an indexed move and issued a `MOVE` notification. Lists with equal members in different orders compared unequal. [Ecore feature metadata](https://download.eclipse.org/modeling/emf/emf/javadoc/2.10.0/org/eclipse/emf/ecore/ETypedElement.html) · [Unique data list](https://github.com/eclipse-emf/org.eclipse.emf/blob/b940729ba08b2499b08ff505d9f038e2e33cdc73/plugins/org.eclipse.emf.ecore/src/org/eclipse/emf/ecore/util/EDataTypeUniqueEList.java) |
| NMF | The generator uses `ISetExpression<T>` and `IOrderedSetExpression<T>` for unique references, depending on ordering. Observable implementations exist for both. | With NMF-Collections 2.0.459, both collections ignored a duplicate string. The unordered set reported additions without an index. The ordered set reported indices 0 and 1. [NMF generator](https://github.com/NMFCode/NMF/blob/b5b45f56ec31860c60e0dcfbffc15844873f7e76/Transformations/Models.MetaTransformation/Meta/Reference2Property.cs#L754-L810) · [NMF collections](https://nmfcode.github.io/collections/index.html) |
| OMG UML | A multivalued property has four possible collection semantics. Independent ordering and uniqueness select set, ordered set, bag, or sequence. | UML defines uniqueness by value for data and by identity for class instances. This distinction matters when a language binding implements model-object equality by content. [UML 2.5.1, §7.5.3.2, Table 7.1](https://www.omg.org/spec/UML/2.5.1/PDF) |

For VMF, the model contract should expose the element type, uniqueness, and whether order matters. A Java API could present an observable `VSet<String>` for capability names. Adding an existing capability or removing a missing one should return `false` and emit no event. Diff, undo, and journal replay should describe added and removed members. A serializer can emit members deterministically while model equality and replay accept any enumeration order. JSON arrays with `uniqueItems` work when schema equality matches the scalar type's equality. The reader should reject duplicate input and identify the offending property.

Model-object sets need an explicit identity decision. In a generated VMF model using `@VMFEquals(ALL)`, editing an object's name after insertion into a Java `LinkedHashSet` caused both `contains(object)` and `remove(object)` to return `false`. The set still held that object. Identity-based membership would allow two distinct objects with equal contents, which Java's `Set<E>` contract forbids. A reference-set design therefore needs to settle its Java contract, containment ownership, opposites, and persistent identity before it can be offered to model authors. Ordered sets would additionally need a public definition of moves and how moves replay. [Java Set contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Set.html)
