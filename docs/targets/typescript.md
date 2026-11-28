# TypeScript language mapping

How the rules of the [portable contract](../../conformance/portable/CONTRACT.md) appear in the generated
TypeScript API. Rule ids (T1, C2, ...) refer to the contract.

Claimed modules: core, views, events, history, reflection, identity-equality, json, validation, collections,
externals, behavior. JavaScript has no equality that a class can define, so TypeScript follows N3 instead of
N2 (see [Equality](#equality-and-copies-q-n)).

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
| `@DefaultValue` | the initial value or content of new objects (see [Defaults](#defaults)) |
| external type | see [External types](#external-types-x) |

## External types (X)

An `@ExternalType` stub names a type of the host language. For TypeScript,
`@Annotation(key = "vmf:typescript:external-type", value = ...)` on the stub names the TypeScript type:

| Stub | TypeScript |
|---|---|
| `value = "\"LOW\" \| \"HIGH\""`, a union of string literals (for Java enums) | an exported alias `type Level = "LOW" \| "HIGH"`; documents write the name and readers reject other names (X1); values compare with `===` |
| `value = "Date"`, a global type | that type |
| `value = "Distance"` and `vmf:typescript:external-module` = `"./hosts.js"` | `import type { Distance } from "./hosts.js"`; a relative module is relative to the generated `model.g.ts`; a name that a generated declaration also uses is imported as `HostDistance` |
| `java.util.UUID`, no annotation | `string` in canonical form: setters accept either case, reject anything but 8-4-4-4-12 hexadecimal digits and keep lower case (X3) |
| `java.lang.Object`, no annotation | `unknown`, an untyped JSON value (X4) |

External types can be single values, lists (`ScalarList`) and method parameters and results.

Untyped values are `null`, strings, booleans, numbers, `bigint`, arrays and plain objects with string keys,
without cycles and nested at most 1,000 deep; `undefined` is stored as `null`. Documents write a safe integer
(`Number.isSafeInteger`) as an integer, any other number with a fraction or exponent and a `bigint` as an integer.
Readers return integers beyond `Number.MAX_SAFE_INTEGER` as `bigint`, other numbers as `number` and JSON objects as
plain objects. A floating-point value that Java wrote as `2.0` therefore reads as `2` and is written back as `2`,
which Java reads as an `Integer`; the contract leaves this unspecified. Content equality compares untyped values
as JSON values, regardless of key order; deep copies share them.

Other external types need a codec when they occur in documents, a `vmf.ExternalCodec<T>` in the
[host bindings](#host-bindings):

```ts
installModelBindings({
    externals: {
        Distance: {
            write: distance => ({ meters: distance.meters }),
            read: json => new Distance((json as { meters: number }).meters),
            equals: (left, right) => left.meters === right.meters,    // optional, default ===
            hashCode: distance => Math.trunc(distance.meters),           // optional, agrees with equals
        },
    },
});
```

`write` returns a JSON value other than `null` (the rules of untyped values apply) and `read` receives it back
(X5). Content equality uses `equals` (X6); deep copies and read-only views share external values.

## Host bindings

A model that needs host code (codecs of the external types its properties use, bound defaults, behaviors)
exports a `ModelBindings` interface that lists exactly that code and an `installModelBindings(bindings)` function:

```ts
import { installModelBindings } from "./model.g.js";

installModelBindings({ externals: { ... }, defaults: { ... }, behaviors: { ... } });
```

A missing member is a compile error at the call; `installModelBindings` also checks at run time that every
binding is present and throws a `TypeError` that lists the missing ones. Model code asks for a binding when it
first needs it: before `installModelBindings` ran, that throws an `Error` naming the binding and
`installModelBindings`, and a model that never reaches a binding (no documents, no delegated calls) works without
one. Installing again replaces the bindings: later documents, new objects and first calls use the new ones, while
an object keeps the default behaviors it already created. Models that need no host code generate neither.

## Defaults

| `@DefaultValue` | TypeScript |
|---|---|
| a literal | the initial value |
| a constant of an enum mapped to a literal union (`Level.MEDIUM`) | the literal (`"MEDIUM"`), no binding needed |
| `java.util.List.of(...)`, `Set.of(v)`, `Map.of(k, v)` of literals on an ordered set, bounded list, set or map (K12) | the initial content |
| `java.util.List.of(T.newInstance())`, or a builder that sets one `String` property, on a set of model objects without opposite (K12) | a new `T` for each object (`Object.assign(new T(), { name: "..." })`, `new T({ name: "..." })` for immutable `T`) |
| any other expression, with `@Annotation(key = "vmf:typescript:bind-default", value = "true")` | `bindings.defaults.<Type>.<property>()`, called for each new object; for a collection it returns the elements (an `Iterable`, entries for a map) |

Defaults of mutable objects are applied in the constructor, after the construction hook (B3); a default replaces
what the hook added to its collection. `@Immutable` types use a default when the constructor gets no value for the
property. Each new object evaluates the effective default of each property once: a redeclared default replaces the
inherited one, whose binding is not called. A nullable property that holds `null` while its default is not `null` is written as `null` in documents,
so that readers keep `null` (J1). Collections with a default are written also when they are empty; a field in a
document replaces the default, and a deep copy holds the original's values (K12). A collection default belongs to a
concrete nongeneric declaration that is the only one in its hierarchy to declare the property: subtypes inherit it
unchanged, and the generator rejects a redeclaration.

## Behavior (B)

`@DelegateTo` methods become methods of the class that call a behavior with the object as first argument. Each
type that declares delegated methods or a construction hook (a `@DelegateTo` on the type) has a behavior
interface, and `bindings.behaviors` creates the behaviors of the types that bind something:

```ts
export interface CounterBehavior {
    onCounterInstantiated?(caller: Counter): void;    // the construction hook
    add(caller: Counter, amount: number): number;
}

installModelBindings({ behaviors: { Counter: () => new DefaultCounter() } });
counter.add(2);                                       // DefaultCounter.add(counter, 2)
counter.replaceBehavior(new TestCounter());           // only this object
```

| Rule | TypeScript |
|---|---|
| a call (B1) | `x.add(2)` calls `add(x, 2)` of the object's behavior and returns its result |
| default behavior (B2) | the binding creates one per object and declaring type, when the object first needs it; Java and C# share one per object among the types that bind the same class |
| construction hook (B3) | `on<Type>Instantiated(caller)` runs in the constructor of every new object (also deep copies and objects read from documents) before the declared defaults are applied; immutable objects have none, as in Java |
| replacement (B4) | `x.replaceBehavior(b)`: `b` implements all delegated methods of `x`'s class (else `TypeError`), never receives the hook and is not copied |
| inherited methods (B5) | a subtype uses the behavior of the type that declares the method until it binds the method again; a method that an interface-only type declares without a binding throws unless the object's behavior was replaced |
| several supertypes | a class declares the delegated methods of the supertypes it implements besides its base class; they call the behavior of the declaring type, and `replaceBehavior` takes a replacement for all of them. As in C#, a concrete implemented supertype is data only or a bound behavior role (its delegated methods all have bindings, from one binding along its base chain); of the construction hooks, the one the model selects for the class runs |

Parameters and results map like properties (`unknown` for `Object`); arrays are `readonly T[] | null` as
parameters and `T[] | null` as results, `List` and `VList` results are arrays, and variable arguments are a rest
parameter whose array the behavior receives. TypeScript has one method per name, so overloads must differ in
their number of parameters; the class method dispatches on the argument count. As in C#, static methods, checked
exceptions and `Object` methods other than `toString()` are rejected.

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
| `@DefaultValue` of a collection (K12) | the content of a new object's collection, applied in the constructor after the construction hook; an immutable value uses it when the constructor gets no value for the property (see [Defaults](#defaults)) |

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

The TypeScript generator rejects models that use generic immutable types, collection defaults that are
redeclared or declared by interface-only or generic types, delegated methods of generic types and overloads
with the same number of parameters. XML, YAML and journals are not available. None of these are part of the
contract.
