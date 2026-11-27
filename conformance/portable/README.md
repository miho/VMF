# Portable contract scenarios

One set of scenario files, one small runner per target language. The rules are in
[CONTRACT.md](CONTRACT.md); this file explains the files, the scenario format and how to run and extend it.

```
conformance/portable/
  CONTRACT.md                 the rules (T, C, R, Q core; V, E, H, F, N optional modules)
  models/                     model declarations, one small package per topic (eu.mihosoft.vmf.portable.<model>)
  scenarios/*.json            111 scenarios, each citing the rules it checks and the Java test it came from
  expected-failures/<target>.txt   known failures per target, one scenario id per line with a reason
  java/                       model generation helper and the Java runner
  csharp/                     the C# runner
  portable.gradle             generation and run tasks
```

## Running

```
./gradlew :vmf:runPortableJava          # Java
./gradlew :vmf:runPortableCSharp        # C# (.NET 8)
./gradlew :vmf:verifyPortableContract   # both
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
`{"obj": label}`.

| Step | Meaning |
|---|---|
| `{"new": l, "type": T, "with": {p: v}}` | create (with initial values: builder or equivalent) |
| `{"set": path, "obj"/"value": x}` | set a property; `path` ending in `[i]` replaces a list element |
| `{"add": path, "obj"/"value"/"objs"/"values": x}` | append one or several elements |
| `{"insert": path, "at": i, "obj"/"value": x}` | insert at index |
| `{"remove": path, "obj": l}` / `"value": v` / `"at": i` | remove the identical object, the first equal scalar, or by index |
| `{"clear": path}` | remove all elements |
| `{"check": path, ...}` | `obj`, `value`, `objs`, `values`, `size`, optional `"unordered": true` |
| `{"let": l, "path": path}` | label the object at a path |
| `{"equal": [p1, p2, ...]}` / `notEqual` | content equality and content hash (Q1) of the first with each other |
| `{"nativeEqual": [...]}` / `nativeNotEqual` | the language's own equality (N1) |
| `{"same": [...]}` / `notSame` | identity |
| `{"copy": path, "as": l}` | deep copy (Q3) |
| `{"readOnly": path, "as": l}` | read-only view (V1) |
| `{"instanceOf": path, "type": T}` / `notInstanceOf` | type check (T1) |
| `{"listen": path, "as": L, "scope": "local"/"recursive"}`, `{"unlisten": L}` | listeners (E) |
| `{"events": L, "are": [...]}` or `"count": n` | compare and clear received events; optional `"anyOrder": true`, `"ignore": [patterns]` |
| `{"record": path}`, `{"recorded": path, "count": n}` | start recording, count recorded changes (H1) |
| `{"undoAll": path}`, `{"redoAll": path}` | stop recording, undo all in reverse order; redo them in order (H2, H3) |
| `{"properties": path, "are": [names]}` | reflected property names in order (F1) |
| `{"annotations": path, "are": [[key, value]]}` | annotations of an object, or of a property if the path ends in one (F2) |

Any edit step may carry `"fails": true`: the edit must be rejected, and the runner checks that every object
reachable from a label is unchanged afterwards.

Expected events match partially: only the fields given are compared (`on`, `prop`, `kind` = `set`, `add`,
`remove` or `replace`, `old`/`new` for scalars, `oldObj`/`newObj` for objects, `values`/`objs` and `index` for
lists). Before comparing, a runner merges the events of one step that differ only in form (E6): consecutive
list events of the same kind on the same property, and chains of set events (`a -> null`, `null -> b`).

## Adding a target

1. Make the generator produce the target from `models/` (`GeneratePortableModels <target> ...`).
2. Write a runner that reads these files and implements the steps above for the modules the target claims.
   The Java runner (`java/PortableRunner.java`, about 1,100 lines) is the reference; the C# runner
   (`csharp/PortableRunner.cs`, about 1,400 lines) follows it step by step.
3. Add a `runPortable<Target>` task and `expected-failures/<target>.txt`.

How the runners reach the generated code:

- Java: `<Type>.newInstance()` and `newBuilder()`, properties through `vmf().reflect()`, `vmf().content()` for
  equality and copies, `vmf().changes()` for listeners and recording, `Change.undo()` and
  `Change.apply(change.object())` for undo and redo.
- C#: constructors and `NewBuilder()`, properties through `<namespace>.ModelSnapshot.Reflection` (model names,
  declared order), lists through their public `Add`, `Insert`, `RemoveAt`, `Clear` and indexer,
  `ContentEquals`/`ContentHashCode`, `Reflection.DeepCopy`, `ModelChanges.For(x)` with `History`,
  `ModelChange.Undo()` and `Redo()`. Interface-only types are abstract classes; several supertypes become
  `I<Name>` interfaces.

## Current results

| Target | Models generated | Scenarios passed | Known failures |
|---|---|---|---|
| Java | 15 of 15 | 108 of 111 | clearing a many-to-one reference notifies the owner twice; content equality of immutable types is identity (2 scenarios) |
| C# | 15 of 15 | 110 of 111 | clearing a many-to-one reference notifies the owner twice |
| TypeScript | 2 of 15 | no runner yet | the generator's baseline profile rejects single and plain-list containment, scalar lists, references with opposites, recursive containment types, `long`/`double`, several supertypes and immutables |

## Where the scenarios come from

The scenarios restate VMF's own Java tests (`test-suite/`) as language-neutral steps. The original suite
(2018 to 2022) defines what VMF set out to do; the tests added in 2025 and 2026 mostly harden Java-specific
behavior.

| Java tests (count) | Topic | Portable as | Scenarios |
|---|---|---|---|
| ContainmentTest (11) | containment moves, opposites | core C | `containment.json` (all 11, plus 13 more) |
| CrossRefTest (3) | references with opposites, events, recorded changes | core R, events, history | `references.json` |
| EqualsTest (15) | content equality, equality modes, deep copy | core Q, equality-modes | `equality.json` |
| LazyInitTest (2), DefaultValuesAndBuildersTest (1), BuilderTest (2) | defaults, initial values | core T | `types.json` |
| PropertyInheritanceTest (1) | several supertypes | core T1 | `types.json` |
| ImmutableTypesTest (1), GetterOnlyTest (1) | immutables, getter-only | views | `views.json` |
| ObservablePropTest (5) | property and list listeners, read-only views | events, views | `events.json`, `views.json` |
| RecursiveListenerTest (original 1) | recursive listeners | events | `events.json` |
| UndoRedoWithContainmentTest (original 4) | containment events, one recorded change per edit | events, history | `events.json`, `history.json` |
| FSMTest, LargeFlowModelTest (create and undo) | undo of a whole construction | history | `history.json` |
| PropertyOrderTest (5), AnnotationsTest (4) | reflection | reflection | `reflection.json` (4 and 2 of them) |
| SetterIdentityTest (16, 2026) | equal but distinct objects | core Q4, C | `equality.json` (6) |
| OneToOneReassignmentTest (11), OneToManyReassignmentTest (13, 2026) | reference takeover | core R2 | `references.json` (6) |
| ContainmentCycleTest (6, 2026) | cycle rejection | core C5 | `containment.json` (3) |
| ReadOnlyExternalListTest (6, 2026) | live read-only lists | views | `views.json` (2) |
| parentcontainment01 (2) | parent lookup through `@DelegateTo` | Java only (delegation) | none |
| ToStringTest (3), ExternalTypesTest (2), StaticReflectionTest (1), PropertyTypeTest (3), CustomLoaderPropertyTest (1), ModelRegistryTest (2), GeneratedDispatchTest (4) | Java API | Java only | none |
| VMFGenerateRuns (25), VMFGeneratorTest (1), MemoryResourceSetTest (2), ImmutableContainmentValidationTest (1) | generator | generator tests | none |
| observer failures, reentrancy, filtered listeners, transactions, journals, diff, list replay (about 140 tests, 2025 to 2026) | Java runtime details | not portable (see CONTRACT.md, unspecified) | none |
