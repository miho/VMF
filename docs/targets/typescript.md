# TypeScript language mapping

How the rules of the [portable contract](../../conformance/portable/CONTRACT.md) appear in the generated
TypeScript API. Rule ids (T1, C2, ...) refer to the contract.

Claimed modules: core, views, events, history, reflection, identity-equality, json. JavaScript has no
equality that a class can define, so TypeScript follows N3 instead of N2 (see [Equality](#equality-and-copies-q-n)).

## Generating

`VMF.generate(GenerationTarget.TYPESCRIPT, outputDir, sourceFiles...)` reads the Java model interfaces and
writes one `model.g.ts` per model namespace (`eu/mihosoft/vmf/portable/tree/model.g.ts`). Generated code is an
ES2022 module that imports the runtime as `@vmf/runtime` (`typescript-runtime/`). Each module exports a
`ModelSnapshot` class for documents.

## Types and values (T)

| Model | TypeScript |
|---|---|
| model type `X` | `class X` |
| create an object | `new X()`, then assign properties |
| `@InterfaceOnly` type | `abstract class`; `instanceof` works through `Symbol.hasInstance` |
| several supertypes | the class extends the first supertype that is not interface-only and implements the others; `instanceof` works for all of them |
| property `name` | accessor `name` |
| `int`, `double` | `number` |
| `long` | `bigint`, exact over all 64 bits |
| `boolean` | `boolean` |
| `String` and other optional values | `string \| null` |
| list property (`T[]`) | `ScalarList<T>`, `ContainmentList<TOwner, TChild>` or `ReferenceList<T>` with `add`, `insert`, `set`, `remove`, `removeAt`, `clear`, `at`, `indexOf`, `contains`, `count` and iteration; an unordered reference collection is a `ReferenceSet<T>` with `add`, `remove`, `clear`, `contains` and `count` |
| `@DefaultValue` (scalars) | the initial value of new objects |

## Containment and references (C, R)

Setting a containment property or adding to a containment list moves the child: it leaves its previous owner,
and its container property changes with it. Opposite references are kept consistent on both sides. An edit
the contract rejects throws (`TypeError`, `RangeError` or `Error`) and leaves the model unchanged: every edit
runs as a unit that is rolled back when it throws.

## Equality and copies (Q, N)

| Rule | TypeScript |
|---|---|
| content equality (Q1) | `x.contentEquals(y)`, `x.contentHashCode()` |
| deep copy (Q3) | `x.deepCopy()` |
| language equality (N3) | `===`, `Map` and `Set` compare objects by identity in every mode |

`@VMFEquals` and `@VMFModel(equality = ...)` have no effect on `===`, `Map` or `Set`: JavaScript offers no way
for a class to change them. Use `contentEquals` and `contentHashCode` where the model declares content
equality, for example to look objects up in a `Map` keyed by `contentHashCode()`.

## Views (V)

`x.asReadOnly()` returns a live view typed by the generated `ReadOnlyX` interface: no setters, lists as
`vmf.ListView` or `vmf.CollectionView` without edit methods, model objects read through it as views too, and
`vmfChanges` for listeners only. A view is frozen: assigning to it throws in strict code (every ES module is
strict) and is ignored in sloppy scripts, as for any frozen object.

`@Immutable` types are classes created with all their values and frozen afterwards:
`new Label({ name: "n", title: "t" })`. Their lists are `vmf.ReadOnlyList`. An immutable object is its own
read-only view, and deep copies share it. A getter-only trait (an `@InterfaceOnly` type whose properties are
all `@GetterOnly`) is an abstract class that mutable and immutable classes implement; mutable classes can set
its properties.

## Events and history (E, H)

| Rule | TypeScript |
|---|---|
| listen | `x.vmfChanges.addListener(change => ..., "local")` (or `"recursive"`, the default) returns a function that removes the listener |
| a change | `{ source, property, kind, oldValue, newValue, values, replaced, indices }` with `kind` `"set"`, `"add"`, `"remove"` or `"replace"` |
| record | `x.vmfChanges.start()`, `stop()`; `history` holds one entry per edit |
| undo, redo | `entry.undo()`, `entry.redo()` |

## Reflection (F)

`x.vmfType` returns `{ name, immutable, properties, annotations, property(name) }`; `properties` are in VMF
property order, each with `name` and `annotations` (`{ key, value }` pairs in declaration order).

## Documents (J)

| Document | TypeScript |
|---|---|
| with `@vmf-id` and `@vmf-type` | `ModelSnapshot.write(x)`, `ModelSnapshot.read(json, X)` |
| plain (J5) | `ModelSnapshot.writePlain(x, X)`, `ModelSnapshot.readPlain(json, X)` |
| plain, references left out | `ModelSnapshot.writePlain(x, X, "omit")` |

Large integers are read and written exactly (`bigint`). References to immutable values are written by value
in every mode.

## Not supported

The TypeScript generator rejects models that use maps, sets or unique lists of scalars, generic immutable types,
behavior delegation, executable defaults or custom external value types. XML, YAML and journals are not
available. None of these are part of the contract.
