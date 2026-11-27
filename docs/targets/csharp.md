# C# language mapping

How the rules of the [portable contract](../../conformance/portable/CONTRACT.md) appear in the generated C#
API. Rule ids (T1, C2, ...) refer to the contract.

Claimed modules: core, views, events, history, reflection, equality-modes, json.

## Generating

The Gradle plugin generates C# with `vmf { targets = ['csharp'] }` (into
`build/generated-src/vmf-main-csharp`), the Maven plugin with `<targets><target>csharp</target></targets>`
(into `target/generated-sources/csharp-vmf`); from code, `VMF.generate(GenerationTarget.CSHARP, outputDir,
sourceFiles...)` does the same. Each model namespace becomes one `Model.g.cs`: `eu.mihosoft.vmf.portable.tree`
becomes the namespace `Eu.Mihosoft.Vmf.Portable.Tree` in `Eu/Mihosoft/Vmf/Portable/Tree/Model.g.cs`. Each
namespace has a static `ModelSnapshot` class for documents, reflection and copies.

Generated code targets .NET 8 and depends on the `Vmf.Runtime` library (`csharp-runtime/`).
`./gradlew :vmf:packCSharpRuntime` packs it as `core/build/target-runtimes/Vmf.Runtime.<version>.nupkg`; it is
not published to nuget.org yet.

## Types and values (T)

| Model | C# |
|---|---|
| model type `X` | `sealed class X` |
| create an object | `new X()`, an object initializer, or `X.NewBuilder().WithName("a").Build()` |
| `@InterfaceOnly` type | `abstract class`; with several supertypes, an `I<Name>` interface |
| property `name` | property `Name` |
| `int`, `long`, `double`, `boolean` | `int`, `long`, `double`, `bool`; boxed Java types become nullable (`int?`) |
| `String` | `string?` |
| list property (`T[]`) | `ScalarList<T>`, `ContainmentList<TOwner, TChild>` or a reference list, each with `Add`, `Insert`, `RemoveAt`, `Clear` and an indexer |
| `@DefaultValue` | the initial value of new objects |

Generated classes also implement `INotifyPropertyChanged`.

## Containment and references (C, R)

Setting a containment property or adding to a containment list moves the child: it leaves its previous owner,
and its container property changes with it. Opposite references are kept consistent on both sides. An edit
the contract rejects throws an exception and leaves the model unchanged; the exception type is not part of the
contract.

## Equality and copies (Q, N)

| Rule | C# |
|---|---|
| content equality (Q1) | `x.ContentEquals(y)`, `x.ContentHashCode()` |
| deep copy (Q3) | `ModelSnapshot.Reflection.DeepCopy(x)` |
| language equality (N2) | `Equals` and `GetHashCode` follow the equality mode |

Without `@VMFEquals` or `@VMFModel(equality = ...)`, `Equals` is reference equality (`INSTANCE`). With
`CONTAINMENT_AND_EXTERNAL` or `ALL`, the class overrides `Equals` and `GetHashCode` with content equality and
implements `IEquatable<X>`. `HashSet` and `Dictionary` then treat content-equal objects of such types as one
key, and an object of such a type must not change while it is a key. The `==` operator is not overloaded; it
stays reference equality for every mode.

## Views (V)

`x.AsReadOnly()` returns an `IReadOnlyX`: get-only properties, read-only list views whose model elements are
read-only views too, and `VmfChanges` for listeners only. `@Immutable` types are classes with get-only
properties, created through the constructor: `new ImmutableType(name: "a")`.

## Events and history (E, H)

| Rule | C# |
|---|---|
| listen | `x.VmfChanges.AddListener(change => ..., ModelChangeScope.Local)` (or `Recursive`, the default) returns an `IDisposable` that removes the listener |
| a change | `ModelChange` with `Source`, `PropertyName`, `Kind`, `OldValue`, `NewValue`; collection changes list the added and removed elements with their indices |
| record | `x.VmfChanges.Start()`, `Stop()`; `History` holds the recorded changes |
| undo, redo | `change.Undo()`, `change.Redo()` |

`Local` and `Recursive` listeners of one object run in the order they were added (E7). A change reaches the
changed object's listeners first, then the recursive listeners of its container, nearest first, whatever the
order in which they were added (E8). A listener may edit the model: the edit is delivered at once, before the
remaining listeners receive the current change, so a listener called later can receive a change whose new value
is no longer current (E9). Such an edit is recorded after the edit that triggered it, so undoing all recorded
changes restores the starting state (H4). Listeners added or removed during a delivery, exceptions thrown by
listeners and recordings started inside a listener are not specified.

Direct property listeners (`AddDirectPropertyListener`) and collection observers are not model listeners: they
run in their own registration order around the model listeners, and edits they make can be recorded before the
edit that triggered them.

## Reflection (F)

`ModelSnapshot.Reflection.Reflect(x)` returns the reflection of an object: `Properties` in VMF property order,
each with `Name` and `Annotations`, and the type's `Annotations`.

## Documents (J)

| Document | C# |
|---|---|
| with `@vmf-id` and `@vmf-type` | `ModelSnapshot.Write(x)`, `ModelSnapshot.Read<X>(json)` |
| plain (J5) | `ModelSnapshot.WritePlain(x)`, `ModelSnapshot.ReadPlain<X>(json)` |
| plain, references left out | `ModelSnapshot.WritePlain(x, ReferenceMode.Omit)` |

`Vmf.Runtime.ReferenceMode` is `Ids`, `Reject` or `Omit`; documents without ids reject references to mutable
objects unless `Omit` is given. References to immutable values are written by value in every mode. XML and YAML
variants of these methods exist but are not part of the contract.
