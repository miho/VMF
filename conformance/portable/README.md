# Portable contract scenarios

One set of scenario files, one small runner per target language. The rules are in
[CONTRACT.md](CONTRACT.md); this file explains the files, the scenario format and how to run and extend it.

```
conformance/portable/
  CONTRACT.md                 the rules (T, C, R, Q core; V, E, H, F, N, J, L, K, X, B optional modules)
  models/                     model declarations, one small package per topic (eu.mihosoft.vmf.portable.<model>);
                              Java host code (enums, value types, delegation classes) next to the vmfmodel package
  scenarios/*.json            the scenarios, each citing the rules it checks and, where one exists, the Java test it came from
  expected-failures/<target>.txt   known failures per target, one scenario id per line with a reason
  java/                       model generation helper and the Java runner
  csharp/                     the C# runner; Hosts/ holds the C# host code of the models
  typescript/                 the TypeScript runner (runner.mjs) and the project that compiles the generated code;
                              hosts/<model>.ts holds the bindings the runner installs and named replacement behaviors
  portable.gradle             generation and run tasks
```

## Running

```
./gradlew :vmf:runPortableJava          # Java
./gradlew :vmf:runPortableCSharp        # C# (.NET 8)
./gradlew :vmf:runPortableTypeScript    # TypeScript (Node.js 22 or newer)
./gradlew :vmf:verifyPortableContract   # all three
./gradlew :vmf:runPortableJava -PportableFilter=containment/   # only matching scenario ids
```

Each model package is generated separately (`generatePortable<Target>`). A package the generator rejects is
listed in `build/generated/<target>-unsupported.txt`, and its scenarios fail for that target. Each runner
prints failures, a summary by module and writes `build/results/<target>.json`. It exits non-zero on a
failure that is not in its expected-failures file, and also when a listed scenario passes again, so the
lists stay accurate.

## Scenario format

```json
{
  "model": "containment",
  "scenarios": [
    {
      "id": "move-between-owners",
      "from": "ContainmentTest.containmentTest",
      "rules": ["C1", "C2"],
      "requires": [],
      "steps": [
        {"new": "ca", "type": "ContainerOne"},
        {"new": "e", "type": "Element"},
        {"set": "ca.element1", "obj": "e"},
        {"check": "e.parentOne", "obj": "ca"}
      ]
    }
  ]
}
```

A scenario id is `<file name>/<id>`. `model` names a package under `models/` and may be overridden per
scenario. `requires` lists optional modules; an empty list means core. `from` names the Java test the scenario
was derived from.

Objects get labels when created (`new`, `copy`, `readOnly`, `let`). A path is a label followed by property
names and list indices: `root.children[0].name`. Property names are the VMF model names; each runner maps them
to its language.

Values: `"obj": label` or `"objs": [labels]` for model objects (compared by identity), `"value": v` or
`"values": [v]` for scalars (JSON numbers, strings, booleans, `null`). In `with`, a model object is written
`{"obj": label}` and a map `{"entries": [[key, value], ...]}`. Values of external types (module externals) are
written in their document form: an enum constant's name, a UUID string, an untyped JSON value, or the JSON of a
host value's codec.

| Step | Meaning |
|---|---|
| `{"new": l, "type": T, "with": {p: v}}` | create (with initial values: builder or equivalent) |
| `{"set": path, "obj"/"value": x}` | set a property; `path` ending in `[i]` replaces a list element |
| `{"add": path, "obj"/"value"/"objs"/"values": x}` | append one or several elements |
| `{"insert": path, "at": i, "obj"/"value": x}` | insert at index |
| `{"remove": path, "obj": l}` / `"value": v` / `"at": i` | remove the identical object, the first equal scalar, or by index |
| `{"clear": path}` | remove all elements (of a list, set or map) |
| `{"put": path, "key": k, "value": v}`, `{"putAll": path, "entries": [[k, v], ...]}` | put one entry, or several in one edit, into a map (K4) |
| `{"removeKey": path, "key": k}` | remove a key from a map |
| `{"find": path, "by": keyProperty, "key": k, "obj": l}` | look up an entry of a keyed containment by key (`find<Property>By<Key>`) and compare it with `l` (`null`: none); a model key is `{"obj": label}` (K6) |
| `{"check": path, ...}` | `obj`, `value`, `objs`, `values`, `size`, `entries` (`[[key, value], ...]` of a map), optional `"unordered": true` for `objs`, `values` and `entries` |
| `{"let": l, "path": path}` | label the object at a path |
| `{"equal": [p1, p2, ...]}` / `notEqual` | content equality and content hash (Q1) of the first with each other |
| `{"nativeEqual": [...]}` / `nativeNotEqual` | the language's own equality (N2, or identity under N3) |
| `{"same": [...]}` / `notSame` | identity |
| `{"copy": path, "as": l}` | deep copy (Q3) |
| `{"readOnly": path, "as": l}` | read-only view (V1) |
| `{"instanceOf": path, "type": T}` / `notInstanceOf` | type check (T1) |
| `{"listen": path, "as": L, "scope": "local"/"recursive"}`, `{"unlisten": L}` | listeners (E); optional `"log": name` also appends each event with the listener's name to a shared call log, and `"react": {"when": pattern, "do": step}` runs an edit inside the listener when an event matches |
| `{"calls": name, "are": [{"listener": L, ...pattern}, ...]}` | compare and clear a call log: which listener received which event, in order (not merged) |
| `{"events": L, "are": [...]}` or `"count": n` | compare and clear received events; optional `"anyOrder": true`, `"ignore": [patterns]` |
| `{"record": path}`, `{"recorded": path, "count": n}` | start recording, count recorded changes (H1) |
| `{"undoAll": path}`, `{"redoAll": path}` | stop recording, undo all in reverse order; redo them in order (H2, H3) |
| `{"properties": path, "are": [names]}` | reflected property names in order (F1) |
| `{"annotations": path, "are": [[key, value]]}` | annotations of an object, or of a property if the path ends in one (F2) |
| `{"write": path, "as": d, "plain": true?, "references": "omit"?}` | write a snapshot document (J), plain without metadata, optionally leaving references out |
| `{"read": d or {"json": ...}, "type": T, "as": l, "plain": true?}` | read a written or a literal document |
| `{"document": d, "is": json}` | compare a written document; key order is ignored, numbers compare by value |
| `{"validate": path, "issues": [{"code": c, "path": p}]}` | validate an object and compare its issues, code and JSON pointer, in reported order (L) |
| `{"call": path, "args": [v], "result": v}` | call a delegated method (B); optional `"resultObj": label`; the last arguments fill a variable-argument parameter |
| `{"replaceBehavior": path, "with": name}` | replace an object's behavior with the host's behavior of that name (B4) |

Any edit step may carry `"fails": true`: the edit must be rejected, and the runner checks that every object
reachable from a label is unchanged afterwards.

Expected events match partially: only the fields given are compared (`on`, `prop`, `kind` = `set`, `add`,
`remove` or `replace`, `old`/`new` for scalars, `oldObj`/`newObj` for objects, `values`/`objs` and `index` for
lists, `keys` with `values` for maps). Runners report a map edit per key (removed, replaced with the new value,
added) and a set edit as removed and added values, whatever form their language uses (K9). Before comparing, a
runner merges the events of one step that differ only in form (E6): consecutive list events of the same kind on
the same property, and chains of set events (`a -> null`, `null -> b`).

## Adding a target

1. Make the generator produce the target from `models/` (`GeneratePortableModels <target> ...`).
2. Write a runner that reads these files and implements the steps above for the modules the target claims.
   The Java runner (`java/PortableRunner.java`, about 1,700 lines) is the reference; the C# runner
   (`csharp/PortableRunner.cs`, about 2,000 lines) and the TypeScript runner (`typescript/runner.mjs`, about
   1,300 lines) follow it step by step.
3. Add a `runPortable<Target>` task and `expected-failures/<target>.txt`.

How the runners reach the generated code (for users, the [language mappings](../../docs/targets/) say the same per language):

- Java: `<Type>.newInstance()` and `newBuilder()`, properties through `vmf().reflect()`, `vmf().content()` for
  equality and copies, `vmf().changes()` for listeners and recording, `Change.undo()` and
  `Change.apply(change.object())` for undo and redo, vmf-jackson (`VMFJacksonModule` with
  `withModelWireTypeAliases(...)` and `withReferences(true)`, or without both for plain documents).
  Validation through `vmf().content().validate()`.
- C#: constructors and `NewBuilder()`, properties through `<namespace>.ModelSnapshot.Reflection` (model names,
  declared order), lists through their public `Add`, `Insert`, `RemoveAt`, `Clear` and indexer,
  `ContentEquals`/`ContentHashCode`, `Reflection.DeepCopy`, `ModelChanges.For(x)` with `History`,
  `ModelChange.Undo()` and `Redo()`. Interface-only types are abstract classes; several supertypes become
  `I<Name>` interfaces. Documents through `ModelSnapshot.Write`, `Read<T>`, `WritePlain<T>` and `ReadPlain<T>`.
  Validation through the generated `Validate()` (`ModelCode`, `ModelPath`) and, with the same expected issues,
  `ModelValidator.NewBuilder(ModelSnapshot.Reflection).Build().Validate(x)`.
- TypeScript: `new` on the classes listed in `ModelSnapshot.registry` (interface-only types are abstract and
  not registered), then plain property assignment for initial values; properties and list objects by model
  name; `instanceof` also for interface-only supertypes a class implements besides its base class;
  `contentEquals`, `contentHashCode` and `deepCopy` on every model object, `===` as the language's equality (N3); `vmfChanges.addListener(listener,
  "local" | "recursive")` (returns a function that removes the listener), `vmfChanges.start()` and `history`,
  whose entries have `undo()` and `redo()`. Documents through `ModelSnapshot.write`, `read`, `writePlain` and
  `readPlain`. `long` values are `bigint`; the runner parses large JSON integers exactly and keeps the key
  order of literal documents. `@Immutable` types are created with `new Type({ ...values })` and frozen; their
  lists, sets and maps are `vmf.ReadOnlyList`, `vmf.ReadOnlySet` and `vmf.ReadOnlyMap`. Sets through `add`,
  `addAll`, `remove`, maps through `put`, `putAll`, `remove`, keyed lookups through the generated
  `find<Property>By<Key>`. `asReadOnly()` returns a live view typed by the generated `ReadOnly<Type>` interface,
  without setters or list edits. Reflection through `vmfType` (`properties`, `annotations`, `property(name)`).
  Validation through `validate()` (`modelCode`, `modelPath`).
- Java also edits sets through `java.util.Set`, reference sets through `RefSet` and `OrderedRefSet`, and maps
  through `java.util.Map`; keyed lookups return `Optional`. C# edits sets and maps through `Add`, `AddRange`,
  `Remove`, `Put`, `PutAll`, and calls `Find<Property>By<Key>`.

Host code: Java finds enums, value types and delegation classes on the classpath and converts external values
with Jackson; `replaceBehavior` instantiates the named class of the model's package and calls
`vmf().behavior().set(...)`. C# implements the generated partial methods (`CreateExternalValueCodecs`,
`CreateDefault<Property>`, `CreateDefaultBehavior`) in `csharp/Hosts` and replaces behaviors with
`ReplaceBehavior(new Vmf.Portable.Hosts.<name>())`. The TypeScript runner calls the generated
`installModelBindings` with the `bindings` that `hosts/<model>.ts` exports, converts external values with the
installed codecs, and takes replacement behaviors from its `replacements`.

## Current results

| Target | Models generated | Scenarios passed | Known failures |
|---|---|---|---|
| Java | 23 of 23 | 225 of 225 (the identity-equality scenario does not apply) | none |
| C# | 23 of 23 | 225 of 225 (the identity-equality scenario does not apply) | none |
| TypeScript | 23 of 23 | 222 of 222 (identity-equality; the 4 equality-mode scenarios do not apply) | none |

The first runs found three bugs that are fixed now: clearing a many-to-one reference notified the owner twice
(Java and C#), and content equality of `@Immutable` types was identity (Java). The JSON module led to one
change: documents without reference IDs used to leave out references to mutable objects in Java (and in C#'s
`WriteWithoutReferences`); all three languages now reject them by default and leave them out only on request:
Java `ReferenceMode.OMIT`, C# `ReferenceMode.Omit`, TypeScript `"omit"`. The externals and behavior modules
found that vmf-jackson could not read a list of external object values (fixed), that TypeScript documents left
out a cleared value with a non-null default instead of writing `null` (fixed, J1), and that Java builders set
every property after the construction hook ran, so what the hook set is lost for built objects (unspecified).

Before this contract the TypeScript generator accepted 2 of the 15 models. Supporting the core took about 700
lines of runtime code and 230 lines of generator changes; the views and reflection modules and immutable values
in documents took about 500 more lines of runtime code and 300 of generator changes. The validation module needed
no change in Java or C#; TypeScript gained value constraints with about 220 lines of runtime code and 150 more
lines of generator code.

## Where the scenarios come from

The scenarios restate VMF's own Java tests (`test-suite/`) as language-neutral steps. The original suite
(2018 to 2022) defines what VMF set out to do; the tests added in 2025 and 2026 mostly harden Java-specific
behavior.

| Java tests (count) | Topic | Portable as | Scenarios |
|---|---|---|---|
| ContainmentTest (11) | containment moves, opposites | core C | `containment.json` (all 11, plus 13 more) |
| CrossRefTest (3) | references with opposites, events, recorded changes | core R, events, history | `references.json` |
| EqualsTest (15) | content equality, equality modes, deep copy | core Q, equality-modes, identity-equality | `equality.json` |
| LazyInitTest (2), DefaultValuesAndBuildersTest (1), BuilderTest (2) | defaults, initial values | core T | `types.json` |
| PropertyInheritanceTest (1) | several supertypes | core T1 | `types.json` |
| ImmutableTypesTest (1), GetterOnlyTest (1) | immutables, getter-only | views | `views.json` |
| ObservablePropTest (5) | property and list listeners, read-only views | events, views | `events.json`, `views.json` |
| RecursiveListenerTest (original 1) | recursive listeners | events | `events.json` |
| UndoRedoWithContainmentTest (original 4) | containment events, one recorded change per edit | events, history | `events.json`, `history.json` |
| FSMTest, LargeFlowModelTest (create and undo) | undo of a whole construction | history | `history.json` |
| PropertyOrderTest (5), AnnotationsTest (4) | reflection | reflection | `reflection.json` (4 and 2 of them) |
| SetterIdentityTest (16, 2026) | equal but distinct objects | core Q4, C | `equality.json` (6) |
| WorkshopSnapshots, VMFJacksonModule tests | snapshot documents | json | `json.json` (14) |
| MultiplicityTest (8), ManagedSettingsTest (6), KeyedPropertyTest (14), CollectionContractTest (9), ReferenceEndsTest (18), 2025 and 2026 | sets, ordered sets, bounds, maps, keyed containment, collection defaults | collections | `collections.json` (29) |
| OneToOneReassignmentTest (11), OneToManyReassignmentTest (13, 2026) | reference takeover | core R2 | `references.json` (6) |
| ContainmentCycleTest (6, 2026) | cycle rejection | core C5 | `containment.json` (3) |
| ModelValidationTest (3, 2026) | multiplicities, ranges, lengths, patterns, paths | validation | `validation.json` (12) |
| ReadOnlyExternalListTest (6, 2026) | live read-only lists | views | `views.json` (2) |
| parentcontainment01 (2), delegationinherit, vflow delegates | `@DelegateTo` methods and construction hooks | behavior | `behavior.json` (8) |
| ExternalTypesTest (2), journalvalues, devcom, vmf-jackson external_types01 | enums, UUIDs, untyped values, host value types | externals | `externals.json` (14) |
| ToStringTest (3), StaticReflectionTest (1), PropertyTypeTest (3), CustomLoaderPropertyTest (1), ModelRegistryTest (2), GeneratedDispatchTest (4) | Java API | Java only | none |
| VMFGenerateRuns (25), VMFGeneratorTest (1), MemoryResourceSetTest (2), ImmutableContainmentValidationTest (1) | generator | generator tests | none |
| observer failures, reentrancy, filtered listeners, transactions, journals, diff, list replay (about 140 tests, 2025 to 2026) | Java runtime details | not portable (see CONTRACT.md, unspecified) | none |
