# TypeScript language mapping

How the rules of the [portable contract](../../conformance/portable/CONTRACT.md) appear in the generated
TypeScript API. Rule ids (T1, C2, ...) refer to the contract.

Claimed modules: core, views, events, history, reflection, identity-equality, json, validation, collections. JavaScript has no
equality that a class can define, so TypeScript follows N3 instead of N2 (see [Equality](#equality-and-copies-q-n)).

## Generating

The Gradle plugin generates TypeScript with `vmf { targets = ['typescript'] }` (into
`build/generated-src/vmf-main-typescript`), the Maven plugin with
`<targets><target>typescript</target></targets>` (into `target/generated-sources/typescript-vmf`); from code,
`VMF.generate(GenerationTarget.TYPESCRIPT, outputDir, sourceFiles...)` does the same. Each model namespace
becomes one `model.g.ts` (`eu/mihosoft/vmf/portable/tree/model.g.ts`) that exports a `ModelSnapshot` class for
documents.

Generated code is an ES2022 module that imports the runtime as `@vmf/runtime` (`typescript-runtime/`).
`./gradlew :vmf:packTypeScriptRuntime` packs it as `core/build/target-runtimes/vmf-runtime-<version>.tgz`, which
`npm install` accepts; it is not published to the npm registry yet.

## Types and values (T)

| Model | TypeScript |
|---|---|
| model type `X` | `class X` |
| create an object | `new X()`, then assign properties |
| `@InterfaceOnly` type | `abstract class`; `instanceof` works through `Symbol.hasInstance` |
| several supertypes | the class extends the first supertype that is not interface-only and implements the others, which can be concrete too; it declares the properties of the implemented supertypes itself; `instanceof` works for all of them |
| property declared again (T7) | one accessor; a new `@DefaultValue` is the initial value of the subtype's objects. A getter-only property of an interface-only type that subtypes narrow or declare as a containment end is an abstract getter there, and each subtype implements it |
| generic type (T8) | `abstract class Series<T>`; a `Number` bound becomes `N extends number \| bigint`, a model bound `E extends Entry`; a subtype binds the arguments (`class Temperatures extends NumericSeries<number>`), and its documents use the argument types |
| property `name` | accessor `name` |
| `int`, `double` | `number` |
| `long` | `bigint`, exact over all 64 bits |
| `boolean` | `boolean` |
| `byte`, `short`, `float` | `number` (T6); `float` keeps the precision of `number`, Java and C# round to `float` when they read a document |
| `char` | `string` of one UTF-16 code unit; the default is `"\u0000"` |
| `String` and other optional values | `string \| null` |
| list property (`T[]`) | `ScalarList<T>`, `ContainmentList<TOwner, TChild>` or `ReferenceList<T>` with `add`, `insert`, `set`, `remove`, `removeAt`, `clear`, `at`, `indexOf`, `contains`, `count` and iteration; an unordered reference collection is a `ReferenceSet<T>` with `add`, `remove`, `clear`, `contains` and `count` |
| `@DefaultValue` (scalars) | the initial value of new objects |

## Collections (K)

| Model | TypeScript |
|---|---|
| `Set<T>` of scalars | `vmf.ScalarSet<T>`: `add`, `addAll`, `remove`, `clear`, `contains`, `count`, `toArray` and iteration; values compare like a JavaScript `Set` |
| `@Multiplicity(kind = ORDERED_SET)` list | a `ScalarList<T>` or `ReferenceList<T>` that holds each element once: `add` returns `false` for an element it holds, `insert` and `set` reject one |
| `@Multiplicity(upper = n)` list of scalars | a `ScalarList<T>` that rejects `null` and edits beyond `n` (`RangeError`); `addAll` adds several values as one edit |
| `VMap<K, V>` | `vmf.ScalarMap<K, V \| null>`: `put(key, value)` (inserts or replaces in place, returns the previous value), `add(key, value)` (rejects a present key), `putAll(entries)`, `get(key)` (`undefined` for an absent key), `has`, `remove`, `clear`, `count`, `keys()`, `values()`, `entries()`, `toMap()`; iteration yields `[key, value]` in insertion order |
| `Set<T>` of model objects | `vmf.ReferenceSet<T>`, also with an opposite; `@Contains Set<T>` is a `vmf.ContainmentSet<TOwner, TChild>` |
| `@Contains @Keyed(by = "sku")` | a `ContainmentList` (or `ContainmentSet`) that checks the keys, and `owner.findItemsBySku(key)` returning the entry or `null`; the read-only view has the same lookup |
| in a read-only view or an `@Immutable` type | `vmf.ReadOnlySet<T>` and `vmf.ReadOnlyMap<K, V>` (typed `vmf.CollectionView` and `vmf.MapView` in `ReadOnlyX`); immutable values take sets as `Iterable<T>` and maps as `Iterable<[K, V]>`, for example a `Map` |

A set or map edit is one change: `{ kind: "add" | "remove" | "replace", values, keys }` (maps carry `keys`, sets no
`indices`). Undo restores a removed map entry at its former position. Documents write sets as arrays and maps as
JSON objects (`String` keys) or arrays of `{"key": k, "value": v}`.

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

`"local"` and `"recursive"` listeners of one object run in the order they were added (E7). A change reaches the
changed object's listeners first, then the recursive listeners of its container, nearest first, whatever the
order in which they were added (E8). A listener may edit the model: the edit is delivered at once, before the
remaining listeners receive the current change, so a listener called later can receive a change whose new value
is no longer current (E9). Such an edit is recorded after the edit that triggered it, so undoing all recorded
changes restores the starting state (H4). Listeners added or removed during a delivery, exceptions thrown by
listeners and recordings started inside a listener are not specified.

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

## Validation (L)

| Rule | TypeScript |
|---|---|
| validate | `x.validate()` returns a frozen array of issues for `x` and everything it contains; an empty array means valid |
| an issue | `{ code, path, message, modelCode, modelPath, severity }` |
| code and path as the contract defines them (L7) | `modelCode` (`"vmf.range"`) and `modelPath` (`"/segments/0/start"`) |
| TypeScript code and path | `code` (`"range"`) and `path`, the member path (`"segments[0].start"`) |

Declared constraints report `"error"` issues; the messages are those of C#, for example
`"a finite number >= 0 and < 10"`. `long` ranges compare `bigint` values exactly. A `float` property holds a
`number`; its range is checked on the value rounded to float precision (`Math.fround`), the value Java and C# read
from a document. `@Length` counts code points, not `string.length`. A portable `@Pattern` becomes a `RegExp`
without flags in which `$` matches only at the very end, and `test` finds a match anywhere in the value (L5). A
Java-only pattern (`@Pattern(value = "...", portable = false)`) needs one
`@Annotation(key = "vmf:pattern:typescript", value = "...")` with an ECMAScript regular expression, which is
searched with the `u` flag; the generator rejects the model without it. Unlike Java and C#, TypeScript accepts an
element beyond the upper bound of a scalar list; validation reports it (L2). Validation keeps its own stack, so deep
containment chains do not exhaust the call stack.

## Not supported

The TypeScript generator rejects models that use generic immutable types, behavior delegation, executable
defaults, `@DefaultValue` on collections or custom external value types. XML, YAML and journals are not
available. None of these are part of the contract.
