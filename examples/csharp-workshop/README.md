# VMF Workshop in Java, C#, and TypeScript

The example generates all three targets from [Workshop and Station](src/main/vmf/eu/mihosoft/vmf/example/workshops/vmfmodel/Workshop.java),
[Catalog](src/main/vmf/eu/mihosoft/vmf/example/workshops/vmfmodel/Catalog.java),
and [ExternalInventory](src/main/vmf/eu/mihosoft/vmf/example/workshops/vmfmodel/ExternalInventory.java).
Use the repository Gradle wrapper with the VMF Java toolchains, a .NET SDK
that can build and run .NET 8, and Node.js 20 or newer with npm. The TypeScript
check installs the compiler from its lockfile. The TypeScript runtime has no
runtime package dependencies.

From the repository root, run

```shell
./gradlew :vmf:verifyTypeScriptWorkshop
```

Use `gradlew.bat` on Windows. The task generates, compiles, and runs all
three targets. Each target reads both other targets' files. Use
`:vmf:verifyCSharpSnapshots` for a Java and C# check without Node or npm,
or `:vmf:verifyCSharpWorkshop` for their in-memory graph comparison.

## Reference-aware snapshots

The Workshop fixture contains both workshops under one site. A favorite
reference can then point to a station in either workshop. The Catalog fixture
uses `Shelf<T extends Product>` instantiated with `Instrument`, with a
`Thermometer` subtype in the collection. The readers restore the concrete
subtype and the original identity of the featured item. They accept forward
references and reject duplicate, dangling, and wrong-type IDs.

In C#, use the generated `ModelSnapshot.Write(root)` and
`ModelSnapshot.Read<Catalog>(json)`. In TypeScript, use
`ModelSnapshot.write(root)` and `ModelSnapshot.read(json, Catalog)`.
In Java, register the VMF Jackson module with
`withReferences(true).withModelWireTypeAliases(Catalog.class)`.

Declare a stable subtype name once with `@Annotation(key = "vmf:wire:type", value = "...")`.
Use `vmf:jackson:rename` to choose a wire property name. The Catalog fixture
uses `items` for the inherited `products` property.

The codecs share `@vmf-type` and document-local `@vmf-id` conventions. Use the
expected root type when reading a snapshot. C# and TypeScript expect
canonical JSON scalar tokens. [VMF Jackson format](../../jackson/README.md#reference-aware-json-and-schema)

## Plain external JSON

The [inventory fixture](fixtures/inventory.json) uses an external schema
with fields such as `warehouse_id`, `stock_items`, and `part_no`.
All three readers build typed objects from it, and all three writers
reproduce its JSON shape and values, including zero and false, without
adding VMF metadata.

Use the standard VMF Jackson module with reference mode disabled for the
inventory. In C#, use `ModelSnapshot.ReadPlain<ExternalInventory>(json)`
and `ModelSnapshot.WritePlain(inventory)`. In TypeScript, use
`ModelSnapshot.readPlain(json, ExternalInventory)` and
`ModelSnapshot.writePlain(inventory, ExternalInventory)`.

Plain mode uses the supplied root type and the model's declared containment
types. The C# and TypeScript plain writers reject populated non-owning
references and subtypes that need a discriminator. Use reference-aware
snapshots for those graphs. Java's default mapper retains its existing
polymorphism and reference behavior.

## Generate your own model

Call the Java API with the desired target and one model package per invocation.

```java
VMF.generate(GenerationTarget.CSHARP, outputDirectory, modelSourceFiles);
```

The generator CLI accepts the target, output directory, and Java source paths.

```shell
./gradlew :vmf:generateModel --args="csharp build/my-model/csharp path/to/Model.java"
./gradlew :vmf:generateModel --args="typescript build/my-model/typescript path/to/Model.java"
```

The TypeScript files import the [local runtime package](../../typescript-runtime)
as `@vmf/runtime`. The [example package](typescript/package.json) shows how to
link it. Compile the C# output into a .NET 8 project that references
[csharp-runtime/Vmf.Runtime.csproj](../../csharp-runtime/Vmf.Runtime.csproj).

Generated Java and C# files are under `build/generated` in this example.
Generated TypeScript is under `typescript/build/generated`. The exchanged
documents are under `build/snapshots`.

## XML snapshots in C#

Use `ModelSnapshot.WriteXml(model)` and `ModelSnapshot.ReadXml<Catalog>(xml)`
for reference-aware snapshots. The plain equivalents are `WritePlainXml` and
`ReadPlainXml`. The conformance suite exchanges external inventory, defaulted
scalar fields, polymorphic catalog models, and recursive archive graphs with
Java Jackson.

## Supported C# and TypeScript declarations

Both targets support mutable models, single inheritance, named model-bounded
generic specializations, string, boolean, 32- and 64-bit integer, and double
properties, scalar lists, single-valued and ordered list containment including
recursive types and container properties declared on base types, single-valued
and ordered list references with or without an opposite, unbounded unordered
identity reference sets, literal scalar defaults, content equality, and deep
copies. Both reject containment cycles. Multiplicity validation runs over
containment. The [portable contract](../../conformance/portable/CONTRACT.md)
scenarios check this shared behavior on Java, C#, and TypeScript.

TypeScript represents `long` as `bigint`. It does not yet support several
model supertypes, immutable types, read-only views, change listeners, or
recording. Its snapshot JSON does not yet write scalar lists, reference lists,
or 64-bit integers.

The C# backend also generates live read-only projections of these mutable
models and standalone immutable types with scalar properties and single
immutable references. It preserves shared immutable values in snapshots.
C# supports all Java primitive and boxed scalar types and validates
multiplicity, range, Unicode length, and portable pattern constraints. It exposes
the VMF issue code and JSON Pointer path as ModelCode and ModelPath.
Floating-point range boundaries use the JVM running the generator. The shared
fixtures use Java 11 for generation and validation. C# also supports recursive
ordered identity containment, singular containment, exact reciprocal endpoints
with inherited concrete types, and ordered reference sets and sequences.
Sequences preserve repeated identities and null slots in reference-aware JSON.
Mutable C# models also support scalar sequences, ordered and unordered scalar
sets, and concrete scalar maps. JSON uses objects for string-keyed maps and
typed key-value arrays for other scalar keys. Managed collections preserve
VMF equality and distinguish an absent entry from a present null.
Keyed containment generates typed lookups on mutable models and their live
read-only views. The tests cover scalar keys and forward model-reference keys.

Mutable models provide `NewBuilder()` and typed `With...` methods.
Portable scalar literal defaults apply to new instances and omitted JSON fields.
An explicit null overrides a nullable default and survives a snapshot round trip.

Use `VmfChanges` on a mutable C# model for local, recursive, and filtered
observation. The change stream includes scalar and direct reference
assignments, containment edits and reparenting, scalar collection edits, and edits to non-owning reference
collections. Recording exposes live read-only history and transaction lists.
Individual edits and groups support undo and redo. If the model state changes
between recorded edits, an undo can apply some entries before encountering
a stale value.

The VMF journal feature preview uses envelope version 1 in JSON, YAML, and
XML. V1 covers scalar and reference assignments, positional collection edits,
managed maps, scalar and reference sets, and introductions of new mutable
objects and immutable values. Use a reference-aware checkpoint when replay
needs shared model identities.
`JournalRecorder<T>` captures changes from a working model and checks each
published frame on a private copy. `Finish()` publishes pending edits.
Disposing a recorder keeps applied edits and omits the pending frame.
`JournalReplayer` checks the checkpoint identity and the expected old values
before returning a fresh model.
See the [journal behavior and format](../../docs/change-journal.md).

The backends report unsupported declarations before writing output. The C#
backend also supports compatible model composition, configured validation
rules, and delegates with CLR bindings. The TypeScript Workshop covers the
shared mutable subset and its plain and reference-aware JSON exchange. The
[C# conformance suite](../../conformance/README.md) lists the additional
declarations, formats, and operations that have executable coverage.
