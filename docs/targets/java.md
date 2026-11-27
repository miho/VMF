# Java language mapping

Java is the reference target: the rules of the [portable contract](../../conformance/portable/CONTRACT.md)
restate what VMF's Java code does. This page says how each rule appears in the generated Java API. Rule ids
(T1, C2, ...) refer to the contract.

Claimed modules: core, views, events, history, reflection, equality-modes, json.

## Generating

The VMF Gradle and Maven plugins compile the model interfaces of a `vmfmodel` package and generate Java
sources; `VMF.generate(outputDir, interfaces...)` does the same from code. Generated code depends on
`vmf-runtime`; documents use `vmf-jackson`.

## Types and values (T)

| Model | Java |
|---|---|
| model type `X` | interface `X` with a generated implementation |
| create an object | `X.newInstance()`, or `X.newBuilder().withName("a").build()` |
| `@InterfaceOnly` type | interface without factory methods |
| several supertypes | the interface extends all of them |
| property `name` | `getName()`, `setName(value)` |
| `int`, `long`, `double`, `boolean` | the same primitives, never `null`; boxed types (`Integer`, ...) allow `null` |
| `byte`, `short`, `float`, `char` | the same primitives (T6); boxed types allow `null` |
| `String` and other object values | references that may be `null` |
| list property (`T[]`) | `VList<T>`, a `java.util.List` that reports its changes |
| `@DefaultValue` | the initial value of new objects |

## Containment and references (C, R)

Setting a containment property or adding to a containment list moves the child: it leaves its previous owner,
and its container property changes with it. Opposite references are kept consistent on both sides. An edit
the contract rejects (a containment cycle, for example) throws an unchecked exception and leaves the model
unchanged; the exception type is not part of the contract.

## Equality and copies (Q, N)

| Rule | Java |
|---|---|
| content equality (Q1) | `x.vmf().content().equals(y)`, `x.vmf().content().hashCode()` |
| deep copy (Q3) | `x.vmf().content().deepCopy()` |
| language equality (N2) | `equals` and `hashCode` follow the equality mode |

Without `@VMFEquals` or `@VMFModel(equality = ...)`, `equals` is identity (`INSTANCE`). With
`CONTAINMENT_AND_EXTERNAL`, `equals` and `hashCode` are content equality; with `ALL`, references count too.
Hash-based collections such as `HashSet` therefore treat content-equal objects of such types as one element,
and an object of such a type must not change while it is a key in one.

## Views (V)

`x.asReadOnly()` returns a `ReadOnlyX`: getters only, lists as read-only lists of read-only views, and
`vmf().changes()` for listeners. `@Immutable` types have no setters; they are built with
`X.newBuilder()...build()`, and their lists cannot change.

## Events and history (E, H)

| Rule | Java |
|---|---|
| listen | `x.vmf().changes().addListener(change -> ..., recursive)` returns a `Subscription`; `unsubscribe()` removes it |
| a change | `change.object()`, `change.propertyName()`; `propertyChange()` (old and new value) or `listChange()` (added, removed or replaced elements with indices) |
| record | `x.vmf().changes().start()`, `stop()`; `all()` lists the recorded changes |
| undo, redo | `change.undo()`; `change.apply(change.object())` |

Local and recursive listeners of one object run in the order they were added (E7). A change reaches the changed
object's listeners first, then the recursive listeners of its container, nearest first, whatever the order in
which they were added (E8). A listener may edit the model: the edit is delivered at once, before the remaining
listeners receive the current change, so a listener called later can receive a change whose new value is no
longer current (E9). Such an edit is recorded after the edit that triggered it, so undoing all recorded changes
restores the starting state (H4). Listeners added or removed during a delivery, exceptions thrown by listeners
and recordings started inside a listener are not specified.

Raw VCollections listeners on a property's list (`list.addChangeListener(...)`) are not model listeners: they run
in their own registration order around the model listeners, and edits they make can be recorded before the edit
that triggered them. Up to VMF 0.4.0, an ancestor's recursive listener that was added first ran before the
changed object's own listeners, and an ancestor's recording recorded a listener's edit before its cause.

## Reflection (F)

`x.vmf().reflect().properties()` lists the properties in VMF property order; `x.vmf().reflect().annotations()`
and `property.annotations()` return the `@Annotation` key/value pairs; `propertyByName(name)` finds one property.

## Documents (J)

vmf-jackson writes and reads snapshot documents with Jackson's `ObjectMapper`:

```java
VMFJacksonModule module = VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL);
ObjectMapper mapper = new ObjectMapper().registerModule(
        module.withModelWireTypeAliases(Catalog.class).withReferences(true));   // @vmf-id and @vmf-type
String json = mapper.writeValueAsString(catalog);
Catalog copy = mapper.readValue(json, Catalog.class);
```

Without `withReferences(true)` the documents are plain (J5): references to mutable objects are rejected, or
left out with `withReferences(VMFJacksonModule.ReferenceMode.OMIT)`. References to immutable values are
written by value in every mode.
