# Native C# conformance

Run the C# suite from the repository root with the Gradle wrapper and a .NET
SDK that can build and run .NET 8.

```shell
./gradlew :vmf:verifyCSharpConformance
```

The cross-language rules are the [portable contract](portable/CONTRACT.md);
this suite tests the C# implementation in much more detail.
[TEST-AREAS.md](TEST-AREAS.md) assigns its tests to areas and says which areas
the contract covers.

Gradle generates Java and C# model classes from the same Java interface
declarations. The generated Java classes, running on the Java VMF runtime,
are the behavioral reference for the shared cases. The gate runs the Java
and native .NET tests, plus the Workshop behavior and JSON checks. Test
results and generated interchange documents are under `conformance/build`.
The TypeScript regression gate remains
`:vmf:verifyTypeScriptWorkshop`. The [portable contract](portable/CONTRACT.md)
and its shared scenarios (`:vmf:verifyPortableContract`) cover the behavior
all targets share.

GitHub CI runs both target gates on Windows and Linux. The Linux job also
validates the generated Draft-07 schemas independently with `jsonschema`.

The Java scalar membership gate runs against the published VCollections
dependency by default, including failed-callback replay. A companion build of
VCollections supplies the preparation hooks for reentrant edits and recorders
opened inside a direct listener. Supply that build from a local Maven
repository to exercise those boundaries against the same model declarations.
The JVM cases and Java-to-C# exchange automatically include prepared membership
when VMF links both hooks. The Java exchange writer refreshes a capability
marker so C# uses only the current run's prepared membership documents.
Set the test environment variable to require both hooks and fail if either
is unavailable.

```shell
VMF_CONFORMANCE_PREPARED_MEMBERSHIP=1 ./gradlew -Dmaven.repo.local=/path/to/local-maven \
  :vmf:verifyJavaScalarMembershipObserverConformance
```

## Existing wire formats

Plain JSON uses model field names and configured wire aliases to match
external documents. Reference-aware JSON uses the existing VMF type names
and reference IDs. The exchange tests also exercise the corresponding
XML and YAML profiles. In typed and reference-aware JSON, the generated
Draft-07 schemas accept the configured polymorphic type names. Readers
and schemas recognize the preferred alias, registered previous aliases,
and qualified model names. A nested object with no discriminator uses
its declared concrete type. A subtype or a declaration with no concrete
default requires a type discriminator. An interface-only root has no
fallback. The typed profiles load a registered concrete implementation
of that role, and the Draft-07 schema lists those implementations. Use
the generated C# `WriteAs<T>` family or Java's
`withRootTypeDiscriminator(true)` when the reader expects an interface-only
root in JSON, YAML, or XML.

Journal envelopes use version 1 for positional and scalar edits, external
values, string-keyed maps, scalar sets, typed maps, identity reference sets,
and immutable references. The paired Java and C# tests read the same
JSON, XML, and YAML journal documents.

## Shared expectations

- [Workshop operations](fixtures/workshop-operations.json) define expected
  containment order, reference identity, inverse links, and model validity
  after each accepted or rejected edit. Java and C# each check the fixture.
- [Immutable selection](fixtures/immutable-selection.json) tests forward
  references and aliases across immutable nodes. The same tests exchange
  documents written by Java and C# in both directions.
- [Immutable portfolio](immutable-models/src/main/vmf/eu/mihosoft/vmf/conformance/immutables/vmfmodel/ImmutablePortfolio.java)
  verifies defensive collection copies, identity membership, structural
  equality and inheritance, and omitted versus explicitly null defaults.
  Its JSON tests cover Java's polymorphic by-value output, reference-aware
  round trips, and metadata-free external JSON.
- Feature bundles can restore an immutable owner through its declared base.
  The concrete value is its own read-only attachment key. Repeated values
  retain one identity within an entry, and each entry restores independently
  in reference-aware JSON, XML, and YAML.
- [Scalar extremes](fixtures/scalar-extremes.json) cover all primitive and boxed
  VMF scalars, including signed 64-bit bounds, signed floating-point zero,
  subnormals, nonfinite values, and UTF-16 characters. Java and C# exchange these
  values in plain and reference-aware JSON.
- [Validation cases](fixtures/scalar-validation.json) compare model paths,
  diagnostic codes, and severity for multiplicity and declared length, range,
  and pattern constraints. The floating-point edge cases use Java 11 semantics.
  A repeated child in an ordinary owning list reports its object diagnostics
  once at the first occupied slot. Distinct children with equal content report
  their own diagnostics, and a wire alias leaves the logical path intact.
- A concrete scalar redeclaration can choose its own JSON field name when
  the inherited traits agree on their storage contract. Both trait setters
  still address the same backing property. The sibling type can retain the
  inherited wire name.
- Configured validation runs typed object and graph rules in containment-first
  order. It verifies reference traversal by identity, read-only projections,
  and exactly one validation pass after each supported import profile hydrates.
  A Java-specific `@Pattern(portable = false)` can supply its CLR regex with
  `@Annotation(key = "vmf:pattern:csharp", value = "â€¦")` on the same getter.
  Each inherited pattern keeps its own binding. Runtime validation checks the
  native pattern, and Draft-07 output includes the accompanying portable
  constraints. Singular multiplicity cases include disabled scalar slots,
  required inverse containers, and validation after the owning link is restored.
  Relationship bounds also cover bounded identity reference sets and
  disabled singular ownership and references.
  The stock validation model checks the bound whole-string regex on import
  and keeps it out of the portable schema. Its reference-aware fixture fills
  the omitted inverse from the persisted endpoint link.
- [Recursive archive](fixtures/archive-references.json) exercises ownership
  across singular and ordered containment, reciprocal edits, inherited
  inverses, cycle rejection, ordered reference identity, and repeated aliases
  and null slots. Both runtimes write and read the reference-aware graph.
- [Scalar collections](fixtures/scalar-collections.json) cover nullable
  sequences, ordered unique values, unordered sets, and concrete typed maps.
  Tests check atomic edit rejection, Java floating-point equality, live views,
  and plain and reference-aware JSON in both directions.
  Stable sort reports sparse changes on ordinary sequences and a whole-range
  replacement on checked or ordered unique scalar lists. Both runtimes exchange
  the V1 edits and test native undo, redo, and apply on a compatible owner.
  Java `removeIf` and C# `RemoveWhere` select individual scalar or non-owning
  reference slots before committing one sparse removal. The tests preserve
  unselected repeats, verify no edit on predicate failure, and exchange V1
  journals in JSON, XML, and YAML.
  Native scalar map and set edits can be applied to an unrelated writable
  model with the same logical property name and concrete collection types.
  The target's bounds govern admission, and the target records its own old
  values using its logical names even when its snapshot aliases differ.
  C# `ReplaceAll` stages a scalar or non-owning reference transform before
  replacing every position in one edit. The exchange covers explicit
  identity transforms, checked validation, and normalization at a reciprocal
  singular inverse while keeping the V1 replacement one operation.
  Java's ordinary VMF list stages the transform too. An operator failure
  leaves the range unchanged, and an empty replacement emits no change or
  V1 operation.
- [Java](java/ObservationConformance.java) and [C#](csharp/ObservationTests.cs)
  exercise scoped observers and recording for scalar and direct reference edits.
  The tests cover recursive delivery after attachment, nested writes, listener
  failures, transaction boundaries, and restoring previous values.
- Portable history replays a direct reference replacement or scalar-list edit
  before edits made by its observers. The paired tests also check native
  callback and history ordering.
- Managed collection observation checks cover indexed edits, identity-set
  membership, and keyed changes with explicit null values. The
  [scalar membership traces](fixtures/scalar-membership-observers.json) specify
  cases for direct and modeled subscribers of mutable and read-only scalar
  maps and unordered scalar sets. For maps, obtaining the read-only collection
  places its relay in mutable source order. That relay reads the projected
  subscribers at its turn. Unordered scalar sets capture both direct subscriber
  lists before dispatch, then deliver the mutable lane before the read-only
  lane. An already captured modeled group reads its own subscribers at its turn
  in either collection. The fixture also checks what native history contains
  when the recording relay runs before or after a direct subscriber. The
  failure cases compare the first exception and its suppressed exceptions,
  then replay the committed edit from an independent checkpoint in all three
  V1 formats.
- The [scalar journal](fixtures/scalar-journal.json) and its
  [checkpoint](fixtures/scalar-journal-checkpoint.json) exercise V1 JSON
  replay in both directions. The tests check named bindings, frame boundaries,
  exact numeric and character encodings, and failure isolation.
- The positional journal exchanges direct references and ordered edits with
  repeated aliases and null slots. C# also verifies replay of ownership moves
  between lists and singular slots for objects already in the checkpoint.
- Containment observation cases check notification order during reparenting
  and replacement, nested write guards, listener errors, and undo in Java and C#.
  Both runtimes deliver live scope groups in their registration order on the
  edited object. Reparenting can detach and reattach recursive groups and change
  that order. Native scalar history can record a nested edit first when an
  earlier live group writes before the recording group runs. Portable journals
  replay both edits in mutation order. Native recording lifecycle cases cover
  scalar, direct reference, and singular ownership delivery. A portable
  recorder opened from a
  scalar, direct reference, or singular ownership observer starts from the
  committed state in its checkpoint.
  Subsequent edits are recorded, including edits made within that observer.
- Default and builder cases verify inherited defaults, missing fields,
  explicit null overrides, repeatable builder inputs, and JSON in both
  directions.
- Membership journals exercise string maps, typed maps, scalar sets, and
  identity reference sets against fresh Java and C# checkpoints.
  The callback reentry fixture records nested membership edits in C# and
  replays them in Java with the published VCollections dependency.
- Creation journals exercise mutable object creation and immutable value
  creation, including cross-references, subsequent edits, forward
  dependencies, shared identities, and replay from a fresh checkpoint.
  The collection creation exchange also verifies initial maps and sets, including
  present-null map entries and typed keys with distinct signed zeros.
- Journal XML exchanges cover scalar, positional, creation, and membership
  records, fresh checkpoint replay, and base64-encoded control characters.
  The JSON and XML readers recover the same operations.
- External value exchanges use the same UUID codec ID for Java UUID and C# Guid.
  They preserve scalar and nullable sequence entries, including fields on
  newly contained objects, in JSON, XML and YAML journals.
- Journal history cases seek across overlapping checkpoints, reject unsuitable
  records, and verify that concurrent seeks produce independent model copies.
  Replay preserves every reachable object ID and bound type, including when
  the journal has no frames. Recorder startup also verifies copied property
  values before it takes ownership of native history. C# rejects a snapshot
  factory that returns a live mutable checkpoint object before hydrating it.
  In-memory histories also preserve aliases in generated checkpoints that
  exceed the snapshot JSON depth limit. A failed later frame leaves both the
  original checkpoint and the successful earlier prefix available for retry.
  Live copies and recorder shadows preserve instance overrides of managed scalar
  lists, sets, and maps. Direct scalar and legacy-list overrides follow Java
  clone behavior. The overrides add no metadata to V1 journal documents.
- [Keyed inventory](fixtures/keyed-inventory.json) verifies live typed lookups,
  inherited keys, atomic rekeying and reparenting, and forward reference keys.
  Both languages exchange plain and reference-aware JSON.
  Compatible owning traits can share a keyed slot. A local field alias is
  used in the snapshot while journal replay resolves the logical property.
  An effective keyed relationship can also be reached through its ordinary
  parent list. Both interfaces expose one owning collection, and edits through
  either interface replay as standard V1 positional edits.
  Unordered keyed owners replay transfers, reference-key changes, and child
  creation from independent Java and C# checkpoints in all three V1 formats.
  A rekey from the source removal callback replays before the destination adds
  the child, and pending lookup reflects the new key during that callback.
- XML fixtures verify renamed external fields, explicit nil, polymorphism,
  reciprocal containment, nullable list slots, and managed scalar collections
  in both directions. They also exchange immutable polymorphic values without
  graph IDs. The collection cases include exact floating-point values and
  duplicate-key rejection across equivalent numeric spellings.
  Both readers skip unknown XML model fields, including repeated elements in
  contained objects, and preserve known fields and defaults.
  In legacy string lists, a bare or explicitly closed empty element is an
  empty string. Use xsi:nil for a null item.
  An empty `xsi:nil` collection wrapper clears declared defaults like any
  other empty wrapper. C# rejects nil wrappers with non-whitespace text or
  child elements to prevent dropping supplied data.
- YAML fixtures exchange metadata-free, typed by-value, and reference-aware
  snapshots, plus an immutable reference journal. They cover exact scalar widths, UTF-16
  characters, null defaults, managed map keys, and repeated graph aliases.
- Literal collection defaults appear in Draft-07 using their snapshot shape.
  When the literal satisfies the declared bounds, the schema permits omission.
- The Draft-07 schema cases cover all three JSON profiles, keyed collections,
  UUIDs, scalar widths, defaults, and named constraints. The independent
  standards check reads the actual Java and C# documents and exercises invalid
  inputs. Run `python conformance/schema/verify.py conformance/build/interchange/schema`
  with the Python `jsonschema` library after the Gradle gate.
  Typed-by-value and reference-aware schemas select the concrete root by its
  type tag. The declared concrete root can also be read without a tag.
  The stock alarm policy exercises the same inputs in both model readers
  and both sets of schemas. Runtime diagnostics identify `alarmLimit`,
  and the configured schemas require the wire field `alarm-limit` when
  an alarm is enabled.
- The .NET tests also cover native read-only members, live views of generic
  relationships, and structural equality for separately constructed values.
- Reflection and traversal check Java's logical property order and inverse
  access, inherited metadata, live read-only projections, cycles, and
  repeated immutable occurrences.
- The hierarchy exchange checks compatible secondary traits, keyed containment
  declared on a secondary trait, interface-typed references, and a closed
  model-bound generic diamond. Both model branches see one storage slot and
  the same live read-only view.
- A mutable data model can combine compatible concrete declarations. The
  first declaration supplies the C# base class, and generated interfaces
  supply the secondary contracts. Those interfaces share the model's fields,
  read-only view, and reflection metadata. The exchange checks inherited
  defaults and a snapshot property declared through a concrete secondary.
  An unordered owning set declared there shares live membership with the
  concrete model and preserves inverse ownership through both projections.
  Its V1 exchange replays child creation and removal alongside an alias update
  from an independent checkpoint in JSON, YAML, and XML.
- Immutable data values can combine compatible concrete immutable parents.
  The primary base and secondary read-only contracts share one immutable
  object. Java and C# exchange defaults, nullable collection slots, and
  polymorphic values in both snapshot profiles.
- Exact redeclarations across compatible data bases share the backing
  field and effective wire metadata. The original Jackson story model
  is included in stock compilation. Its interface-only transition list
  exposes scalar values because every concrete implementation explicitly
  redeclares that list as non-owning with the same shape.
- Reciprocal reference tests cover scalar, positional, and identity-set ends,
  occupied endpoints, grouped inverse edits, snapshot reconstruction, and exact
  reference membership journal operations.
  A symmetric self-reference such as `Partner.partner` uses `PartnerReference`
  in generated C# when the member would otherwise collide with its class name.
  Its VMF property and wire field remain `partner`.
- Shared getter-only interfaces cover mutable and immutable implementations,
  inherited defaults, collection projections, polymorphic aliases, and
  mixed references in reference-aware and by-value JSON.
  An immutable model can redeclare a scalar from a getter-only trait and
  apply its own default, wire alias, and equality annotations. Both traits
  and reflection see the same field.
  Concrete immutable builders can also bind executable scalar defaults
  through these data traits. The port configuration fixture exchanges
  aliased strings and named enum constants in plain JSON, YAML, and XML.
  A fieldless marker used by an immutable concrete declaration can share
  the same contract with mutable implementations. The stock unparser model
  exercises this through `LangElement`.
- Covariant singular model getters use one storage slot through the base and
  derived contracts. Validation, reflection, and snapshots use the narrower
  declared type, including the effective field alias. Java and C# exchange
  the same documents for mutable and immutable models.
  The unchanged stock location hierarchy also exercises one live property
  through several getter-only branches and their read-only views.
  The stock property-order hierarchy exercises a getter-only `Object` field
  whose concrete implementation selects a boxed scalar. One backing field
  supplies both views, and snapshots and journals use the concrete type.
- The numeric generic exchange checks named Double and Long specializations,
  forwarding of a Number bound, nullable values and map entries, typed keys,
  and concrete range validation.
- When a generated C# type and one of its properties would have the same
  name, the type can use a `Model` suffix. The logical type and property
  names stay the same in metadata and snapshots. The generic map exchange
  exercises `PricesModel<K, V>` with its inherited `Prices` map.
- The unbounded scalar exchange specializes one declaration for strings,
  boxed integers and booleans, and a forwarded Number bound. The generated
  C# APIs retain boxed nulls in typed fields and collections. Java and C# read
  each other's snapshots and replay the string-map journals.
  Direct and named generic secondary data bases also share one backing
  collection with their typed live read-only views. Boxed integer and string
  closures preserve reflected types and null list slots in both snapshot
  modes and exchange V1 scalar and positional journals in JSON, YAML, and XML.
- Native deep copies preserve cycles, repeated references, keyed containment,
  detached targets, and shared immutable values. Copying a read-only root
  produces a read-only projection of the new graph.
- Shallow copies share materialized ordinary lists and keep separate membership
  for managed collections. Edits to a shared list produce callbacks and history
  for each owner. C# allows empty owning fields and rejects populated containment
  and relationships with a singular opposite that is already occupied. A
  shallow copy of a reciprocal list owns its membership and links to a
  many-valued opposite through the normal relationship operations.
- Content comparisons check structural and identity policies, ignored fields,
  polymorphic exact types, read-only views, and ordered and unordered graph
  edges. The content API compares a read-only projection through its backing
  model. In both runtimes, structurally equal graphs hash equally even when
  different numbers of objects represent the same cycle. An identity-policy
  projection has its own public object identity. Model list lookups also
  accept the corresponding mutable or read-only alias. The paired cases compare
  a 20,000-edge acyclic graph and deep cycles with unordered membership.
- Generated `ToString()` and `ModelSnapshot.Reflection.FormatSummary(model)`
  present the VMF diagnostic layout. The exchange checks logical field names,
  inherited `@IgnoreToString`, read-only views, recursion markers for repeated
  mutable identities, and repeated immutable values. Summary text uses
  collection iteration order and host text for external values. Floating-point
  spellings can vary across Java versions. The summary and snapshot serializers
  have independent output formats.
- Source-bound diffs check frozen collection changes, stale-value checks,
  containment aliases, and cross-owner moves between matching concrete types.
  `DiffSharedProperties(source, desired)` supports different root types with
  compatible shared scalar fields, scalar collections, and relationships.
  Owning and reciprocal fields require matching modeled opposites and keys.
  The source keeps its own type and source-only fields. An owned member is
  imported across different root types even when a key-equal source member exists.
  The tests cover an imported alias cycle, null reference edits, and an immutable
  desired model whose collections replace the contents of live mutable storage.
  Diff compares map mappings regardless of iteration order. Applying a captured
  map edit, importing a new child, and merging a selected map preserve each map's
  captured key order in its live storage.
  Merge applies compatible set fields and appends positional collections on a
  copy of the concrete template. A reference selected from a broadly typed
  field can fill a narrower template field when its value fits. Merge
  respects the overlay's defaults and supports mutable and immutable overlays. A single input retains its copy
  semantics. An immutable template keeps its identity when the overlay makes
  no writes, and rejects attempted writes even when the values match.
  Replacing an immutable reference on a mutable owner can be undone
  by restoring the original instance. When an unkeyed move can be identified
  unambiguously, it reuses the source object and keeps its aliases. Tests
  check links between copied and retained branches, reverse undo of owning
  swaps, and edits to fields marked `@IgnoreEquals` on ordered owning sets.
  An attachment listener sees the new subtree and its inverse links when
  the root edge is published. Stale undo compares singular model links by
  identity and scalar values by their value equality.
- Containment traversal visits every occupied slot, including repeated
  children, and expands each mutable identity once. Null slots do not
  interrupt traversal in mutable models or their read-only projections.
- `ModelSnapshot.Reflection.Visit` delivers paired depth-first entry and
  exit callbacks. An expanded mutable identity receives one pair, and
  immutable values receive a pair for each occurrence. The Java and C#
  traces cover aliases, cycles and read-only views.
- `ModelSnapshot.Reflection.OpenContainmentCursor(root)` opens a writable
  depth-first traversal. `TryPeek` skips null slots and keeps the last returned
  edge selected. Singular and ordered list slots can be replaced. Insertion
  is available on ordered lists, including ordinary lists with nullable slots.
  Replacing an owning slot skips the replacement subtree. If another
  ordinary slot still owns the old child, its descendants finish traversing
  before the later alias is yielded. Insertion skips the new slot.
  `Current` still reports the last returned object.
  Read-only and immutable roots, incompatible children, and insertion of a
  child already owned by the same list raise argument errors. Changing an
  active owning edge or collection makes peek, advance, and edit attempts fail
  with `InvalidOperationException`. The exception also applies when there is
  no current slot or the edge does not support an edit, for example at the root
  or in an unordered set. Disposed cursors raise `ObjectDisposedException`.
  Edits use the model's ordinary rules for ownership, nullability, and events.
  The JVM cursor tests keep the exact selected slot through repeated
  occurrences, descendant exits, and terminal lookahead. They verify
  exhaustion after the last node and reject edits to a slot that another
  mutation has replaced.
- `ModelSnapshot.Reflection.OpenGraphCursor(root)` selects live containment
  and reference edges and visits each mutable identity once (`UniqueNode`),
  as does Java's generated `Content.iterator()`. Pass `UniqueProperty` to
  select each aliased occurrence.
  Writable singular references, ordinary reference lists and ordered reference
  sets support replacing the selected slot. Replacement keeps the original
  object's scheduled descendants. Reference lists accept aliases and null.
  Ordered reference sets enforce identity uniqueness. Insertions in either
  collection skip the new slots in call order. After insertion, replacement
  is available again at the next selected slot. Indexed changes in a
  non-owning sequence refresh lookahead. Edits require the selected identity
  to occupy its original slot, and structural changes invalidate its position.
  Detaching the incoming reference leaves an entered frame available.
  An unordered owning or non-owning set also exposes its current slots
  to the graph cursor for replacement and insertion. Those edits preserve
  the live slot order. Observers, history and V1 journals record unordered
  membership, so replay can use a different iteration order.
  `Traverse` and `Visit` continue to support read-only projections.
- Reflected properties expose declared defaults, distinguish cleared
  nullable values from defaults, and reset via the usual mutation APIs.
  Primitive defaults retain their declared property types. Reflected scalar
  defaults on a read-only view track overrides on the underlying instance.
  Repeated type and inherited field annotations retain their order in both
  mutable reflection and read-only views.
- `ModelProperty.SetDefault` sets a local instance default. Fields holding
  their former default update through the usual mutation APIs. Collection
  defaults are validated and copied. Copies and imports preserve current
  values and start with declared defaults.
- Managed ordered collections, single-member sets, and single-entry maps
  can take literal defaults from the corresponding Java collection factories.
  The declarations are decoded to typed values. The stock tests cover live
  reset, and the exchange covers escaped strings, omitted fields, and explicit
  empty collections on both runtimes.
- A singleton identity reference-set default can construct a fresh model or
  supply one string literal to its builder. Stock tests compare independent
  defaults, live resets, and existing snapshot representations for omitted
  and explicitly empty fields.
- `ModelSnapshot.TypeCatalog` exposes static fields, annotations, bounds,
  and applied supertypes without constructing model instances. The catalog
  includes interface-only declarations and resolves subtype relationships
  through closed generic applications and secondary traits.
- When several parents declare one scalar property with matching metadata,
  the first contributing parent's literal default applies to the shared
  storage. Mutable and immutable builders and read-only views expose the
  same value. Reflected reset and snapshots use the receiving type's default
  when the document leaves the field absent. A first parent without a declared
  default contributes its implicit value. A concrete scalar redeclaration
  can supply a new literal while keeping the rest of the inherited metadata.
- Exact scalar redeclarations in single-parent hierarchies retain inherited
  storage and use the effective defaults, wire aliases, and validation
  constraints. Mixed interface-only traits keep their individual getter-only
  contracts, and concrete mutable classes expose the inherited setters.
- A nonliteral scalar default on a concrete model can opt in with
  `vmf:csharp:bind-default=true`. A typed `CreateDefault<Property>()`
  partial method supplies the C# expression. Constructors, builders, and
  reflected resets run the provider at the observed Java points. Within
  a primary model hierarchy, a mutable constructor applies the effective
  defaults in model order after the construction hook. Redeclarations can
  replace the provider, supply a literal, or clear the default. Immutable
  builders evaluate the effective provider when staging a value. The type
  catalog flags the bound default, and schema inspection leaves the provider
  unevaluated. A getter-only trait can also own an executable scalar
  default shared by its direct immutable implementations. Each builder or
  snapshot import stages it once. A concrete literal or cleared default
  supersedes the trait provider, and reflected defaults are evaluated on
  demand. Immutable snapshot readers stage executable scalar defaults once,
  including when the wire supplies an override. The builder captures any
  reference-set defaults before scalar providers and before nested values
  are materialized in input field order.
- Reciprocal containment and references work through primary inheritance,
  abstract endpoints, and named closed generic specializations. Tests
  compare mutation order, inverse notifications, and undo, and verify
  that an incompatible subtype is rejected before changing either end.
  A closed specialization can explicitly redeclare the same relationship
  slots and supply their effective wire aliases.
- An unordered reciprocal set can target a named getter-only role. A
  concrete member exposes the writable inverse and both views observe the
  same relationship. Reference-aware snapshots reconstruct either omitted
  end. Both runtimes replay V1 JSON, XML, and YAML from independent checkpoints.
- Ordered sets and ordinary lists can also target that role. Ordered
  sets retain distinct identities and their positions. An ordinary list
  preserves null slots. When an edit introduces a repeated member with
  a scalar inverse, normalization removes the earlier occurrence.
  Stable sorting emits one sparse positional change and preserves inverse
  memberships and untouched slots. Java and C# exchange the resulting V1
  record in JSON, XML, and YAML and verify replay against fresh checkpoints.
  Java native undo and reapplication may report the unchanged slots between
  the first and last changed index because the published VList batch setter
  takes a contiguous range. A comparator cannot address individual repeated
  or null occurrences. The original sort and its V1 record remain sparse.
- Multiple owning fields can share one read-only `@Container` inverse. The
  graph tests move a child between singular and collection slots, verify
  the inverse and its notifications, and exchange reference-aware and by-value
  snapshots and V1 journals in JSON, YAML and XML.
- A writable `@Container` inverse also works with alternate owning fields
  declared directly on the same owner. Assigning the current owner preserves
  the current field. Assigning a different owner uses its declared canonical
  field. Clearing the inverse detaches from the field that owns the child.
- A getter-only model base can expose relationships owned by its concrete
  implementations. The connector fixture checks that construction hooks,
  mutable and read-only views, and reflection all see the same storage.
  Moving a connection updates its sole owner and uses ordinary V1 records
  in the Java and C# exchange.
- An unordered owning set attaches members by identity, exposes a live
  read-only set, and defers multiplicity checks to model validation. The
  tests cover native copies and undo, identity-preserving patches with keyed
  owners, and membership transfers and creations across JSON, YAML and XML.
- Ordinary containment lists carry null slots and can temporarily hold
  repeated children during positional edits. Moving such a child emits
  one sparse removal from its old owner. Native undo restores every slot with
  the same child instance on Java and C#. Undo of a positional insertion or
  replacement checks the affected slots and can preserve unrelated appends
  from a listener. A graph patch can add and undo an occurrence of a retained
  child. Snapshots serialize unreferenced repeats as separate values and
  reject repeated definitions of one reference ID. The V1 journal tests
  replay sparse transfers, null edits, and aliases restored and removed
  within one frame from a fresh checkpoint in JSON, YAML, and XML. Published
  frames require distinct owning entries.
  Reinserting children already owned by the same list records one V1
  replacement with the final positions. An unchanged order produces no edit.
  Direct and indexed bulk inserts can combine retained, new, and transferred
  children. The live edit publishes one destination range change. The V1
  exchange uses existing removal and addition records in the same frame.
  Ordinary and plain keyed owning lists also support a full-range transform.
  The paired tests cover preserved aliases, atomic key and cycle rejection,
  and a V1 source removal followed by a full destination replacement.
  Replacing an empty owning list leaves the journal and history unchanged.
  Predicate removal on ordinary owning lists preserves unselected aliases
  and restores the original sparse positions on native undo. The V1 exchange
  exercises an alias introduced and selectively removed in one frame.
  Plain keyed lists also support sparse predicate removal. Native undo checks
  every restored key before publishing the original positional insertion runs.
  V1 captures the removal before a direct listener's nested edit.
- Exact collection redeclarations reuse the original field and apply the
  effective wire alias, membership, and multiplicity contracts. Closed
  getter-only generic bounds keep mutable projections live and share
  immutable values by identity.
  A named upper bound formed from two getter-only roles exposes both roles
  through one live projection. Its closed selection retains the concrete
  stored type and shares the owned item by identity in reference-aware
  JSON, XML, and YAML.
  An ordinary scalar list inherited through a mixed mutable type stays
  writable through both parent interfaces even when one getter carries
  `@GetterOnly`. Both interfaces share one reflected property and one
  live read-only view. The collection tests exchange snapshots and replay
  V1 journals in JSON, YAML, and XML.
- Mutable scalar lists, ordinary reference lists, and ordinary or keyed
  containment lists support `AddListener` on both the list and its read-only
  view.
  The event identifies the subscribed list and reports the typed values
  and positions. Disposing the subscription stops delivery. The fixtures
  check the collection source, model history,
  canonical projected identities, and sparse ownership transfers.
  Reading the read-only view of an ordinary list reserves a relay among
  the mutable-list listeners. The relay keeps its position while the projection
  is reachable. Subscriptions keep the projected source alive until disposal.
  Scalar and unopposed reference lists with one model owner, and ordinary
  owning lists, deliver direct and modeled callbacks in source registration
  order. The source route is captured before relationship repair. Each
  projected group takes its own snapshot when its relay is reached. Native
  history follows its recording relay, and V1 captures committed edits
  before callbacks can change their operands.
  Shallow-copy aliases emit direct callbacks from the shared list once and
  modeled callbacks for each observed owner. An existing read-only relay
  can pick up a new projected subscription during the same edit.
  Reciprocal lists capture the source registrations before notifying the
  inverse. Both ends can notify observers, and native history records the
  initiating end. The two-list journal cases verify reentry from a source
  or inverse observer against an independent checkpoint in JSON, XML, and YAML.
- Scalar sets and maps support direct `AddListener` subscriptions on both
  mutable collections and read-only views. Membership and entry snapshots
  preserve their comparer. Map edits distinguish an absent key from a
  present key with a null value. The model history and V1 replay cases
  verify that the edit remains recorded when a collection listener throws.
- Unordered reference sets, unordered owning sets and ordered keyed sets
  use modeled change observation. Reading the public set leaves modeled
  callback order unchanged.
  The first projected modeled subscriber reserves a relay that persists through
  unsubscribe and resubscribe. Plain keyed lists reserve their direct relay
  when the projected list getter is read. The
  [paired traces](fixtures/model-identity-set-observers.json) cover local,
  recursive, reentrant and inverse callbacks with canonical member identities.
  [Relationship set traces](fixtures/modeled-relationship-set-observers.json)
  cover ordered keyed membership and edits initiated at either reciprocal end.
  C# ordered keyed sets also expose direct list listeners with indexed payloads.
- Model summaries follow VMF's diagnostic output and honor inherited
  `@IgnoreToString` annotations. The fixtures check cycles, repeated
  references, inherited fields, immutable values, and managed collections.
- Model versions advance for observed changes, including inverse updates.
  Read-only views have an independent local stream with projected event
  payloads and history. A captured edit can apply its scalar or external
  payload to an explicit compatible writable receiver. Ordinary model-list
  edits with only null slots can also be applied. Retained edits keep
  their recorded values and positions after the source changes or its
  history is cleared. The opaque payload guard also recognizes immutable
  CLR values generated with the older typed builder and projection API.
  Version tracking is local runtime state.
- Delegated behavior on standalone mutable models exposes typed methods
  with overloads and variable arguments. A companion C# partial class
  implements the generated `Behavior` interface and
  `CreateDefaultBehavior()` factory. Per-instance overrides stay local to
  the instance. Copies and imports use the default binding, and the wire
  document contains only model data. Managed map import publishes a callback
  for each displaced key in order and one batch for the incoming entries.
  On a standalone mutable type, literal managed collection defaults are
  applied after its construction hook and replace values seeded by that hook.
  An omitted import field keeps the declared default.
  Snapshot reads for standalone mutable types with executable managed defaults
  evaluate builder providers in property order before constructing that
  instance. Present fields supply the imported values, and omitted fields
  use the staged defaults.
  Java snapshot reads obtain metadata from current generated declarations
  without running a constructor hook or default provider. Both first and
  subsequent reads run one real builder and constructor. Earlier Java
  generated models still read through the builder-based metadata path.
  Java alias registration, writer subtype selection, and public supertype
  queries also read declarations without constructing a model.
  The paired JSON, YAML and XML tests verify omitted, supplied and empty
  fields, callback order, and a self-reference in reference-aware imports.
  Nongeneric concrete mutable models can also inherit executable managed
  list and map defaults through a single-primary chain rooted in an
  interface-only declaration. The selected hook, inherited or replaced,
  runs once against the collection instances that later receive the defaults
  or imported values. A compatible scalar list or map can replace or omit
  the inherited default without replacing its live collection. With no
  default, direct construction preserves hook edits and builder-based reads
  replace managed contents with the staged values or an empty collection.
- Delegated inheritance verifies a single selected constructor hook before
  base and derived values, virtual calls through the base class, and concrete
  runtime replacement contracts. Behavior interfaces and model bases use the
  same closed arguments for nullable scalar generics, including boxed numbers
  and forwarded strings. Paired snapshots restore the declared data and run
  the selected construction hook once. Closed numeric model specializations can
  inherit callable signatures expressed in portable scalar or model types.
  A hierarchy's `DefaultBehavior` binds the locally implemented methods.
  Callable signatures can use several closed generic arguments. The applied
  call fixture retains both a named model type and a nullable boxed integer
  through inherited dispatch.
  CLR aliases with identical signatures are diagnosed during generation.
  The tests keep distinct nullable-value and external-reference overloads.
  It also checks nullable native UUID and URI call arguments and results.
  An external type used only in a call needs a native type binding. The
  snapshot codec is registered when a persisted property uses that type.
- Stock `delegationinherit` composes disjoint, unbound callable-only root
  traits. The C# secondary interface exposes its model calls over the same
  instance. One local native binding per concrete declaration shares state
  across calls and its construction hook. Snapshots reload fresh bindings.
- A mutable composition can inherit delegated methods from nongeneric secondary
  roots. An interface-only role can reach the concrete model through multiple
  data traits when the paths inherit the same callable declarations. The native
  binding accepts the secondary interface as its caller and uses one binding
  instance per role on each model. Construction runs the Java-selected hook
  before modeled defaults. Replacement bindings cover both parent contracts.
  JSON interchange preserves polymorphic data and alias identity while reloading
  fresh defaults.
- A bound primary behavior hierarchy can include nongeneric, data-only
  secondary bases. An abstract port keeps its inherited hook and typed argument
  contract. A flow can run its own hook and lazily dispatch inherited methods
  against the same concrete caller. The fixture exchanges both typed JSON modes
  and plain data for an exact hook-only type.
- When multiple direct bound roles over data-only ancestors declare the same method,
  the first Java parent supplies the default. A local override takes precedence
  through each role. Other overloads use their declaring binding, initialized
  on first use unless that binding supplies the selected construction hook.
  A C# replacement implements each common signature once with the composed
  caller type.
- Directly composed roles can share a Java delegate class. Declare one
  C# default factory in `ModelBehaviorBindings` for that class. The binding
  is cached per concrete model and handles the selected construction hook and
  calls from every role that uses that class, including a local declaration.
  A callable shared by several roles has one callback whose `object caller`
  is the original model. Distinct callables and hooks keep their typed callers.
  A subset of the roles can share a class while the other bindings remain independent.
- A concrete mutable declaration composing direct bound roles can have several
  local Java delegate classes. Its generated C# partial class supplies
  `DefaultBehavior_<BindingName>` and `CreateDefaultBehavior_<BindingName>()`
  for each independent local binding. A local method using the roles' shared
  class joins the existing `ModelBehaviorBindings` factory. Only the binding
  for the selected construction hook is initialized eagerly. The fixture
  verifies per-instance state, a whole-model replacement, and both existing
  JSON snapshot modes in Java and C#.
- A composed bound role can itself be a primary or secondary parent. Exact
  competing signatures follow Java's first parent and local overrides,
  including through casts to nested
  companion interfaces. Other overloads retain their original binding.
  The same typed replacements and factories apply, with a companion caller
  when a composed role can be invoked on another model. Both Java snapshot
  profiles restore the selected hooks and fresh delegate state.
- A bound role reached through multiple composed branches still uses one
  provider per owner and Java binding class. Java's first path selects the
  construction hook and competing callable. C# bridges repeated ancestor
  slots for model-wide replacements. The fixture covers both parent orders,
  a redundant direct role edge, and both existing JSON snapshot modes.
- A concrete bound role can inherit through several single-primary models.
  Intermediate declarations can contribute only data or add one local
  delegate class. Compositions use the selected hook and existing lazy
  providers with the actual owner even when that role is secondary.
  The fixture exchanges both JSON modes and invokes replacements through
  the inherited role interfaces.
- Concrete sibling roles can each contribute one local delegate and inherit
  calls from their bound base. When the siblings use the same Java class,
  their local calls share the existing `ModelBehaviorBindings` provider for
  the actual owner. A base bound to a different class has its own provider.
  The fixture covers the selected hook, shared and inherited callable slots,
  both parent orders, local overrides, and one typed replacement across
  the base and both siblings.
- Competing inherited roles can keep different named model result types
  when Java selects the narrower result. C# supplies an explicit wider role
  call and uses the narrower declared result in the shared provider and
  model-wide replacement. Both role views preserve result identity and nulls.
- An ordinary mutable model with a delegated Java `toString()` exposes a
  CLR `ToString()` override. A read-only view forwards to the live model's
  same lazy provider or active replacement and preserves null results.
- A mutable model can delegate Java `equals(Object)` and `hashCode()` through
  one binding declared on the model or inherited unchanged from its primary
  root. C# exposes the corresponding CLR overrides and forwards calls from
  read-only views to the live model, including a derived replacement.
  Structural content comparison and identity-based membership keep their
  existing semantics. The exchange preserves distinct equal objects
  and their reference aliases.
- An immutable model exposes delegated methods through an explicit
  `I<Owner>Calls` interface. The C# partial class supplies a private
  `Behavior` and `CreateDefaultBehavior()` for one Java binding. With multiple
  Java bindings, each `Behavior_<BindingName>` and `CreateBehavior_<BindingName>()`
  is initialized on its first call for that instance. When simple binding
  names coincide, the emitted names include the qualified Java class
  names and each provider keeps its own state. Concrete immutable
  subclasses inherit the base call interface and bind unchanged signatures
  to the concrete value's effective delegates. A covariant override exposes
  the narrower named model result through its own call interface. The
  inherited wider interface calls the same per-value binding. Getter-only
  traits can also supply bindings. An immutable delegated Java `toString()`
  becomes the CLR `ToString()` override, including null results and shared
  lazy provider state. Undelegated values retain their reflection summary.
  A standalone immutable model can also delegate CLR equality and hashing
  through one lazy binding, including any ordinary calls from that binding.
  Explicit C# content comparison remains structural.
  The fixtures
  compare Java's generated implementation cast, the Java case where a
  method-free intermediate trait exposes inherited behavior, and C#'s explicit
  contract. Immutable construction keeps hooks dormant. Snapshots contain
  model data and reload with fresh lazy bindings.
- An immutable composition inherits the call contracts of its concrete
  bases, including secondary data projections. Each binding class has
  one lazy provider per actual composed value. When both parents declare
  the same call, every call interface selects the first parent's binding.
  The fixtures check both parent orders and fresh bindings after import.
  A getter-only data role may precede a concrete immutable base. The
  value retains the declared parent order in its type metadata and
  exposes the same lazy binding through the explicit call contract.
- Mutable local providers and shared role bindings use qualified Java
  class names when simple names coincide. The fixtures check independent
  binding state, shared providers within a role group, and both JSON profiles.
- Delegated methods can take and return closed model applications with one
  supported type argument, including one-dimensional arrays and variable
  arguments. The C# signatures preserve the model argument and nullable boxed
  scalars through inherited method slots. Java erases applied inputs and array
  types in its generated bindings.
- A singular delegated argument or result can use a named mutable abstract model.
  Stock `parentcontainment01` uses a typed C# binding for `CodeEntity.Root()`
  and returns the original concrete model instance.
  The applied-call exchange exercises both a primary class and a secondary
  interface as argument types, including nulls and edits to the same instance.
- Delegated `List<T>` and `VList<T>` results use a writable `IList<T?>` in C#.
  Bindings can return the original scalar, reference, or containment list.
  Tests exercise null slots, ownership, notifications, and inherited calls
  when reloading models. The model cases include both named concrete and
  named mutable abstract element types.
- Feature bundles compose allowlisted model packages in JSON, YAML and XML.
  Attachment lookup respects exact owner and view identity, and reference IDs
  stay local to each bundled model.
- Typed-by-value snapshots follow Java's existing reference policy in all
  three formats. They omit mutable ordinary references and serialize
  immutable values inline. Use the reference-aware profile when aliases or
  mutable identity sets must survive a round trip.
- A Java `@ExternalType` placeholder can name a CLR reference type with
  `vmf:csharp:external-type` or a struct with
  `vmf:csharp:external-value-type`. The generated `ModelSnapshot` partial
  class requires an application-local `CreateExternalValueCodecs()` binding.
  An external codec supplies JSON values and their schema. YAML uses the
  same values, and XML uses the binding's element-content projection.
  A host XML codec can opt into attributes on non-null scalar elements and
  list items. Model elements and null slots retain their existing rules.
  The fixtures exchange URI and structured immutable values in both
  snapshot profiles and replay them through version 1 journals.
  UUID and URI snapshots preserve an explicit clear of a declaration default.
  Ordinary nullable external lists keep declared collection bounds in
  validation and schema. A UUID list preserves null slots and duplicates
  even when its count exceeds the declared upper bound.
  `WithEnum<T>()` binds a native enum by its declared names and supplies its
  string schema. The enum fixture exchanges nullable scalars and list slots
  in all three formats and uses an application codec ID in external
  journal records. It also passes enum bindings through generic model types
  and exercises a nested Java enum. Reflection keeps the canonical Java type
  name while the schema lists the enum names directly for list items.
  `WithValue<T>()` can capture a mutable host value at snapshot time. Read-only
  views still expose the native host instance. The stock `StringBuilder`
  model checks live aliasing and by-value JSON, YAML, and XML exchange.
  An external list element passes embedded VMF metadata field names to its
  host codec in both plain and reference-aware JSON and YAML. Actual model
  elements still resolve their declared runtime types.
  The stock raw `java.util.List` model exchanges nested JSON-compatible lists
  in JSON and YAML, as a value and as nullable slots in a modeled list. Its
  bound callback receives the original model and read-only views preserve live
  host aliases. AWT fields retain native types and live aliases. Stock
  Jackson fails to persist an unconfigured `BufferedImage`. The heterogeneous
  raw-list fixture qualifies JSON and YAML; stock XML does not round-trip it.
  The stock generic series binds the source name `java.lang.Thread.State`
  while Java reflection retains `java.lang.Thread$State`. The stock archive
  replays UUID and revision changes with its original codec IDs and rejects
  a mutable buffer as a journal value.
- Optional mutable Java `Object` slots accept native scalar boxes and acyclic
  lists and string-keyed maps. Snapshots follow Java's document conventions,
  including numeric boxing in JSON and YAML and untyped text in XML. XML
  retains the incoming spelling, while Java 11 and .NET can emit different
  decimal text for the same floating-point bit pattern.
  V1 journals preserve supported scalar kinds and verify the old value against
  the live checkpoint. Mutable untyped collections are snapshot values.
  The stock VFlow fixture exercises Object arguments on delegated methods,
  graph ownership, and exchange of existing snapshots and journals.
- Ordinary `List<Object>` properties support null slots and lookup of nested
  lists and maps by value. JSON and YAML preserve nested values. XML preserves
  null slots and projects scalar elements to text. V1 edits preserve the exact
  scalar kind of values on a native checkpoint. A freshly loaded JSON
  checkpoint reconstructs numeric kinds from the number tokens and can replay
  edits recorded with those same kinds. The built-in V1 scalar kinds cannot
  represent nested list or map edits.
- The original generated JSON model exposes its six value alternatives through
  a common read-only getter. Each concrete branch keeps its typed storage and
  snapshot rules. The fixtures verify alias identity for owned model targets,
  Java's existing handling of detached targets, and V1 edits on the writable
  Object branch.
- A bound ordinary Java map is a host value. The stock map cases verify that
  in-place host mutations do not produce VMF changes. Clearing the host value
  allows managed-map changes to record and replay in V1. The snapshot fixtures
  preserve Java's existing defaults and XML conversion rules.
- Stock DevCom preserves executable enum defaults, `PortInfo` equality, and
  nested device states. Its explicit exception binding follows the existing
  Java JSON and YAML payload, and the message and cause forms accepted by
  the stock XML reader. V1 replay covers enum-bearing immutable creations.
  Both runtimes reject a non-null stock exception in portable recording.
- Starting a journal on an active native recording leaves the existing
  recording intact. A one-shot callback that opens or publishes its own
  native transaction fails and leaves its applied edits on the live model.
  Explicit frame publication rejects earlier native groups, edits during
  publication, and nested recorder lifecycle calls. Published native history
  survives a portable verification failure. `Finish()` accepts valid native
  groups and verifies the resulting journal.
- Journal bindings can assign application type IDs, create model instances,
  and resolve wire property keys. The V1 fixtures exchange mutable and
  immutable creations between Java and C# in all three formats. Both
  runtimes require the property mapping to round-trip to the same wire key.
  A journal opened from a generated checkpoint can keep two equal immutable
  objects under separate application-assigned identities. Replay and history
  seeking keep those immutable objects and use independent mutable roots.
- Native history clears edits before it clears transactions. The mutable and
  read-only fixtures cover reentrant edits and verify that a throwing
  history observer leaves the published transactions available for retry.
- Clearing a singular owning slot publishes one edit on the owner. The
  V1 fixture also verifies replay of earlier journals with a redundant
  null-to-null record following that removal.

## Parity coverage

Each capability needs generated API tests, runtime expectations, and wire
fixtures when it changes persistence. Keep platform-specific binding tests in
the target that owns the binding. A Java-specific delegate, external type,
or executable default requires a corresponding .NET binding.

| Area | Conformance cases |
| --- | --- |
| Native model surface | Typed generics, inheritance, builders, defaults, reflection, traversal, read-only views, immutable values |
| Graph operations | Containment and opposite links, list and set semantics, typed and keyed maps, cloning, diff and merge |
| Validation | Multiplicity, declared constraints, custom rules, property paths and structured reports |
| Observation and history | Scoped delivery, reentrant and throwing observers, transaction boundaries, undo and redo |
| Persistence | Plain and reference-aware JSON, YAML and XML, schema generation, attachments, and checkpoint/journal replay |

C# builders expose `ApplyFrom` and `ApplyTo` for supported writable scalars,
scalar collections, and unopposed references. This includes scalar-only
models and models composed from primary and secondary data roles. Importing
through an applied base type or companion interface stages that role's
properties. Import snapshots collection contents, and `AppendCollections`
selects append or replacement. Applying to a target keeps its managed
collections alive and respects each property's storage constraints.
The behavior covers typed collection and literal scalar defaults, including
inherited literals. Singular references, ordinary reference lists, and
unordered identity sets retain the original targets and their owners. A
reference-set default retains the builder's captured identity.

Mutable builders can import a singular reference through its getter-only
ancestor. Narrowing checks the referenced object's concrete type when the
builder imports the field. A successful assignment updates the same slot
seen by the live read-only view.

Getter-only roles can also supply portable scalar values and collections, and
unopposed references to immutable models. The builder stages only the applied
role's properties. It preserves null values, present-null map keys, and shared
immutable references. Applying to a mutable model uses the same observable
storage as a direct edit.

Immutable builders can import values through the concrete type or a compatible
ancestor. An import keeps an explicit null and snapshots the source collections.
By default, lists append in order, sets retain their modeled membership, and
maps update entries by key. `AppendCollections(false)` replaces the imported
collections. Earlier builds retain their values, including reference identity
and aliases.

Builders for ordinary keyed containment stage child identities and their
owners. Applying checks
keys and cycles before each containment edit. Replacement detaches the old
children, then reattaches staged children and transfers them from previous
owners as needed. Existing observers receive those edits on the same live list.

C# `With` methods validate directly staged keyed children and keep the
previous stage if the new input fails. Nested builders are evaluated when
the enclosing build reaches their owning property. Earlier ownership
callbacks can update a later child's staged key, and attachment validates
the resulting live key. These builds are non-transactional. A later failure
can leave earlier transfers or nested builder effects in place.

Ordinary owning lists retain Java's positional add and transfer behavior,
including null slots and identity coalescing on insertion. Same-source
replacement clears and refills the existing list. Java and C# check for
containment cycles before applying staged properties. They check again before
clearing a replacement list because an earlier callback can change the graph.
Ordinary and ordered owning lists apply their declared multiplicity bounds
at model validation. On an ordinary list, a null slot does not count as a
member for validation.

`ApplyFrom` stages singular owning slots for `Build`, which transfers the
original children to the new owner. A writable container link can also be
staged, so the new model attaches to the imported parent. `ApplyTo` preserves
the target's existing singular owning slots and parent.

An abstract child contract with a shared getter-only container can supply
concrete children to singular and ordinary owning slots. Copying preserves
concrete child identity and applies the existing transfer, null-slot, and
repeated-child rules.
A named, nongeneric getter-only role can be owned through an unopposed
singular slot, ordinary owning list, ordered identity set, or unordered
identity set when every concrete implementation is mutable. This includes secondary role implementations and generic owners
with a named getter-only bound. The live
owner holds the concrete child. Its read-only view projects that same
child, and typed snapshots preserve the concrete type. Reference-aware
snapshots also restore aliases to the owned child. A getter-only child role
can declare a named container inverse to a compatible writable owner.
Concrete children can transfer through that inverse, including between
singular, ordinary, ordered-unique and unordered owning slots. The import
restores the effective inverse and the read-only projection. V1 journal
replay restores the same named inverses during transplants and can reattach
the existing child in a later frame of the same replay after it detaches.
An unopposed owning collection through that role can use a declared
scalar key or a model identity key. Both owner views provide
typed key lookup. Transfers and key changes preserve identity and reject
collisions. A held child view can select its entry for lookup and removal
on the mutable collection. Populated collections keyed by a mutable model
reference require reference-aware snapshots.

An abstract child with a writable reciprocal container can be staged through
an ordinary owning list when the inverse points back to that list. The same
mutators transfer the exact subtype instance and preserve its other links.

A builder for a model with a construction hook can transfer supported fields
from the same model or an ancestor. Applying those fields to an existing
instance retains its captured reference defaults. The construction hook runs
when the builder creates a new instance. Build then replaces any hook-added
managed scalar collection contents with the staged values, or clears them
when no values were staged. Ordinary lists append their staged values to
the hook contents.

Replacing ordered containment through a builder keeps retained children
attached. A size-changing replacement publishes sparse removals, a reorder,
and sparse insertions. Native history and V1 replay preserve the positions
and the resulting inverse ownership.

Unordered owning sets report separate removal and addition membership edits
during replacement. Retained members keep their inverse link. The declared
upper bound is checked by model validation after the builder applies its edits.

An independent concrete model can combine executable scalar defaults,
managed scalar collection defaults, and unopposed reference-set defaults.
Mutable construction evaluates defaults in effective property order.
Builders capture reference defaults first and reuse those identities across
builds. Mutable copy shells skip that capture and preserve or remap the
source identities. Immutable builders keep their captured defaults through
construction and read-only projection.

An unchanged ordered or unordered reference-set default declared on a
mutable base model is inherited along a concrete primary chain. Each concrete
constructor and builder captures its own
default identities. Deep copy remaps them within the copied graph and
shallow copy shares the captured defaults with the source. Scalar and
managed collection defaults can share the same hierarchy when their slots
stay unchanged. Construction follows effective property order. Builder and
copy shells reevaluate non-reference defaults and reuse the reference caches.
A construction hook on a standalone model, a primary base or its descendant
runs once on the concrete owner before any constructor default provider. The
bound fallback is still unavailable during the hook. Mutable reflection reports
null and read-only reflection rejects the lookup. Construction and builds
replace hook-added reference membership with the chosen values. Copies retain
hook-added members before source membership. A concrete descendant runs its
own hook when declared, otherwise it inherits the nearest hook. A primary
reference-default hierarchy can also compose unrelated passive concrete roles
with distinct scalar slots and literal defaults. The companion and read-only
views see the same concrete owner. Constructors, builders and copy shells
retain the primary default provider order and secondary data.
A concrete role can also contribute its own reference defaults. Each provider
is bound on its declaring type, and each composed owner captures a separate
fallback. Effective construction order spans primary and secondary properties.
Builders capture all reference defaults before other providers, and copies
preserve or remap the captures of the copied owner. This projection covers
concrete, nongeneric data roles with distinct property slots.
A hook declared on the composed type or inherited through its primary chain
runs once on the concrete owner before any provider. A reference fallback is
unavailable inside the hook. Construction and builds replace hook-added
members, and copies retain them before copying the source membership.
A hook and an ordinary method with the same behavior binding share the
concrete owner's provider. Builders and copies create a fresh provider for
each new instance.

The C# builder accepts deferred model inputs through
`With<Property>Builder` and `With<Property>Builders`. It snapshots the sequence
of builders when staged. Each outer `Build` invokes the current child builder
once per occurrence, retains ordinary list null slots, and applies the
property's normal ownership and collection rules. A direct `With` or
`ApplyFrom` replaces the deferred input for the affected property.
`ApplyTo` uses the direct staged values with the normal append or replace
rule and does not invoke child builders. Imports of bound external properties
preserve the original host instances, including repeated objects and null
slots in ordinary lists. Imports through a closed generic base stage its
declared properties and preserve reference identity. Applying staged scalar
or ordinary list opposites uses the normal relationship operations. Bulk
edits on reciprocal lists visit inverse occurrences in source order. The
reentry cases reconcile inverses against the current list contents. Builders
with executable defaults stage the provider values once and preserve
explicit nulls, empty collections, and map key presence on import.
Reference collections retain their membership until validation checks
multiplicity. An import leaves a shared getter-only inverse computed by
the owning relationship.

Builders for unbounded ordered identity references preserve Java's positional
edits when copying to an existing model, including links paired with an
unordered inverse. Size-changing replacement records sparse removals, a
retained-member reorder, and sparse insertions. Native undo and V1 replay
restore positions and reciprocal memberships.

The [existing Java test suite](../test-suite/README.md) and
[Jackson tests](../jackson/src/test/java) are the reference inventory.
Port cases with declared input and expected observable behavior so later
targets can reuse the same contract.

The C# gate generates every declaration package in the Java model test
suites and compiles the output with warnings as errors. Host values and
delegated behavior use explicit C# bindings. The gate checks
[the Java model declaration package list](stock-java/packages.txt) against the
packages discovered on disk. Adding a Java model requires C# compile coverage.
Runtime and interchange cases use dedicated fixtures with explicit
expectations in each target.
