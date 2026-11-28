# Areas of the C# conformance tests

The C# conformance suite has 1266 test cases in 215 classes. They were written while
comparing the C# target with Java feature by feature, so they test C#'s implementation, not a contract.
This list assigns every class to one area and says whether the [portable contract](portable/CONTRACT.md)
covers the area, so that each area can be decided: contract module, C#-only, or dropped.

The contract states cross-language rules for 16 of the 24 areas, in 226 scenarios that run on every target; the
column "Contract" names the rules and says where they cover an area only in part. The C# tests of those areas
remain regression tests of the C# implementation. The other areas are Java features without a written
cross-language rule; for them, Java's behavior was the specification.

Counts come from the last test run (one row per test case, including each case of a theory).

## What the suite covers

Measured on 2026-11-28 with `dotnet-coverage` (line coverage of `Vmf.Runtime`; each test class was also run
alone) and JaCoCo (generator lines reached while generating each model set):

- **Runtime.** The suite covers 95.0% of the runtime's 9,207 coverable lines, the portable scenarios 34.7%. All
  but 8 of the lines the scenarios cover are also covered by the suite. The 60% that only the suite covers are
  the features outside the contract: journals, patches, diff and merge, JSON schema, XML and YAML, cursors,
  feature bundles, builders, summaries.
- **Overlap inside the suite.** 130 of the 215 classes (521 cases) cover no runtime line that another class does
  not cover as well. 30 classes (367 cases) reach 95% of the suite's line coverage, 59 classes (605 cases) 99%
  and 92 classes (837 cases) all of it. Line coverage is a lower bound of what a test checks: two tests can
  cover the same lines in different states, which matters most for the listener-order and containment tests.
- **Generator.** The portable, stock (every test-suite and vmf-jackson model) and workshop models reach 5,703
  generator lines for C#. The 36 conformance model sets add 606 lines (9.6%). A single set reaches 348 of them,
  and 308 of those belong to the sets of the behavior, hierarchy, default and applied-call areas; 19 sets reach
  no line of their own.
- **Duplicates.** The 852 test methods contain no copies: none are equal after normalizing literals, and one
  group of three is similar (80% of their token sequences).
- **Time.** The 1266 cases run in 2 to 3 seconds. The gate takes about 7 minutes; that is code generation and
  compilation of the 36 model sets in two languages, not test execution.

The column "Own lines" is the number of runtime lines that only the tests of that area cover.

| Area | Cases | Own lines | Contract | What the tests check | Recommendation | Decision |
|---|---:|---:|---|---|---|---|
| Containment, opposites, references | 169 | 99 | core C, R | moves, ownership, cycles, opposite updates, equal-but-distinct objects, aliasing in bulk edits | Keep as C# regression tests; the contract states the cross-language rules. Review the tests that pin internal details (sparse slots, alias resolvers, held views). | |
| Scalars and scalar lists | 23 | 11 | core T | defaults, extreme values, constraints, capacity checks, set membership | Keep as C# regression tests. | |
| Events | 92 | 18 | E | property, list, set and reference-list events, local and recursive listeners | Keep as C# regression tests. | |
| Listener order and reentrancy | 69 | 1 | E7, E8, E9, H4; the rest deliberately unspecified | the order in which listeners of several objects and scopes are called, edits made inside listeners, subscriptions changed during delivery | The tests follow E7 to E9 and H4; traces that mix model, view and raw listeners are compared per kind. The cases about subscriptions changed during delivery, exceptions and recordings started inside listeners pin behavior the contract leaves open: keep them only as long as C# promises that behavior. | Rules E7 to E9 and H4 decided (2026-11-27); Java and C# changed to them. |
| History | 4 | 0 | H | undo and redo of opposite updates | Keep as C# regression tests. | |
| Views and immutables | 42 | 43 | V | read-only projections, live read-only lists, immutable values and their bases, getter-only traits | Keep as C# regression tests. | |
| Equality and copies | 25 | 27 | Q, N | content equality, equality modes, deep copies; shallow copies (not in the contract) | Keep. Shallow copy: C#-only, or a contract rule if every target should offer it. | |
| Reflection | 14 | 14 | F | property order and annotations; type catalog and default metadata (not in the contract) | Keep. | |
| JSON documents | 31 | 13 | J | reference-aware and plain documents, references by value, real-model round trips | Keep. | |
| XML and YAML | 68 | 32 | none | XML and YAML snapshots, nil collections, XML attributes | C#-only, unless XML or YAML documents must pass between languages (then a module, and TypeScript needs it too). JSON is the exchange format today. | |
| Journals, patches, diff, merge, versions | 147 | 204 | none | the V1 change journal and its file format, positional and creation journals, checkpoints, model patches, merges, shared diffs, model versions | Largest area. If change journals are meant to pass between languages, make them a module with a format specification and a few dozen scenarios once the journal design is final (it is a preview); otherwise C#-only. | |
| Validation and schema | 62 | 188 | L for the declared constraints; JSON schema none | configured validation, constraints, multiplicities, JSON schema generation | The declared constraints are contract module L: keep a few C# cases for the C# API. Application rules and JSON schema generation stay C#-only (most of the own lines are schema generation). | |
| Keyed containment, maps, sets | 62 | 12 | K | keyed collections, maps, identity keys, sets of scalars and their events | Contract module K (29 scenarios on all three targets). Trim to the cases that test the C# collection classes beyond the rules. | |
| Behavior delegation and roles | 35 | 0 | B | @DelegateTo bindings, behavior hierarchies, role variants, applied calls | Contract module B. The tests add no runtime line; their models add generator lines (role resolution). Keep the models in a generate-and-compile check and one test per kind of binding; drop the 13 one-test role variants. | |
| Defaults beyond literals | 114 | 77 | K12 (collection defaults), X2 (enum defaults); the rest none | executable, dynamic, managed, hooked and reference defaults and their precedence | Collection and enum defaults are in the contract. Executable and hooked defaults run host code: C#-only. Trim the variants. | |
| Builders and apply | 55 | 31 | none (API shape is unspecified) | builders, applyFrom/applyTo, appended collections | C#-only. | |
| Cursors and traversal | 34 | 321 | none | containment and reference cursors, traversal | C#-only, or drop the feature if no C# user needs it: these tests are the only cover of 321 lines. | |
| External types, enums, codecs | 54 | 50 | X | bindings of custom Java value types, enums, snapshot codecs, untyped objects | Contract module X. Keep the cases about C# codecs and bindings. | |
| Generics, redeclaration, covariance | 93 | 48 | T7, T8 for redeclared properties and scalar type parameters; the rest none | generic model types and bounds, redeclared and covariant properties, untyped lists, concrete bases | The contract covers what all targets generate. The other cases test the C# generator: keep one per generated shape. | |
| Real-model ports | 37 | 30 | partly | models from existing VMF users (DevCom, MiniClang, VFlow, ...) end to end | Keep as acceptance tests. | |
| Feature bundles | 14 | 240 | none | plugin composition and feature persistence | C#-only. | |
| List operations beyond the contract | 12 | 46 | none | sorting and predicate removal on lists, their undo | C#-only, or contract rules if every target should offer sort and removeIf. | |
| Summaries and toString | 5 | 47 | deliberately unspecified | Java-compatible summary and toString formats | Drop, or keep a minimal smoke test: formats are not part of any contract. | |
| C# platform integration | 5 | 26 | none | INotifyPropertyChanged | C#-only. | |

## Classes per area

**Containment, opposites, references** (169): OrdinaryContainmentTests (35), BulkListAliasTests (19), UnorderedContainmentTests (8), NamedRoleContainmentTests (7), OwningReplacementTests (4), ListReplacementTests (3), HierarchyTests (7), ArchiveTests (8), ReciprocalTests (20), SequenceRoleReciprocalTests (13), InheritedOppositeTests (7), SharedInverseTests (7), NamedInverseTests (2), GetterRoleOwnershipTests (5), GetterRoleReciprocalTests (5), GetterRoleSetOwnershipTests (4), StockReferenceIdentityTests (4), StockCollectionContractTests (3), ShipmentTests (4), ValueMatchingAliasTests (4)

**Scalars and scalar lists** (23): ScalarTests (14), CollectionTests (9)

**Events** (92): ObservationTests (25), ContainmentObservationTests (9), CollectionObservationTests (7), DirectPropertyObservationTests (5), MixedListObservationTests (8), ReferenceListObservationTests (5), ReciprocalListObservationTests (7), ModelIdentitySetObserverTests (12), ModeledRelationshipSetObserverTests (14)

**Listener order and reentrancy** (69): ObserverOrderTests (39), ScalarMembershipObserverRouteTests (30)

**History** (4): ReciprocalPropertyHistoryTests (4)

**Views and immutables** (42): ReadOnlyTests (2), ProjectedRelationshipTests (2), OpaquePropertyProjectionTests (2), ProjectedListLifetimeTests (3), ProjectedListObservationTests (4), ImmutableValueTests (3), ImmutableCollectionTests (7), ImmutableDataBasesTests (3), ImmutableAncestryTests (2), ImmutableSecondaryTests (2), ImmutableParentOrderTests (1), ImmutableCovariantTests (1), ImmutablePortSettingsTests (4), GetterOnlyTraitTests (6)

**Equality and copies** (25): ContentEqualityTests (8), DeepCopyTests (4), MutableEqualityTests (1), InheritedEqualityTests (1), ImmutableEqualityTests (1), ShallowCopyTests (10)

**Reflection** (14): ReflectionTests (5), ReflectionAnnotationTests (2), StockPropertyOrderTests (4), ModelTypeCatalogTests (3)

**JSON documents** (31): TypedValueReferencesTests (9), WorkshopGraphTests (15), StockJsonTests (3), StockCollectionPersistenceTests (4)

**XML and YAML** (68): XmlSnapshotTests (27), YamlTests (20), DeepXmlSnapshotTests (4), DeepYamlSnapshotTests (4), XmlNilCollectionsTests (10), ExternalXmlAttributeTests (3)

**Journals, patches, diff, merge, versions** (147): JournalTests (23), CreationJournalTests (18), JournalHistoryTests (12), PositionalJournalTests (8), CreationCollectionJournalTests (5), MembershipJournalTests (5), ExternalValueJournalTests (7), JournalPreviewVersionTests (6), JournalRegistryCompositionTests (3), JournalBindingTests (3), JournalCheckpointFactoryTests (1), JournalNativeCheckpointFactoryTests (2), KeyedUnorderedJournalTests (2), NamedRoleJournalTests (1), SecondaryReciprocalJournalTests (3), ModelPatchTests (19), ModelPatchAliasTests (1), ModelPatchCopyReferenceTests (2), ModelPatchImmutableTests (1), ModelPatchUndoIdentityTests (2), ModelMergeAliasTests (2), StockModelMergeTests (4), StockModelSharedDiffTests (8), ModelVersionTests (7), ReadOnlyReplayCompatibilityTests (2)

**Validation and schema** (62): ConfiguredValidationTests (21), SnapshotSchemaTests (33), StockValidationSchemaTests (7), OwningValidationTests (1)

**Keyed containment, maps, sets** (62): KeyedTests (23), KeyedUnorderedOwnershipTests (11), KeyedGetterRoleOwnershipTests (4), KeyedOwningPredicateTests (2), KeyedBuilderCallbackTests (1), KeyedRedeclarationTests (2), GenericKeyedTests (3), GenericKeyTests (3), GenericIdentityKeyTests (3), StockPlainMapTests (5), ScalarMapObservationTests (3), ScalarSetObservationTests (2)

**Behavior delegation and roles** (35): BehaviorTests (4), BehaviorHierarchyTests (4), CombinedBehaviorTests (1), AbstractRoleBehaviorTests (1), CovariantRoleTests (1), DeepRoleTests (1), LocalBindingTests (1), NestedRoleTests (1), PrimarySpineTests (1), QualifiedBindingTests (1), RepeatedRoleTests (1), RoleContestTests (1), SecondaryBehaviorTests (1), SharedRoleTests (1), SiblingRoleTests (1), TrioRoleTests (1), ImmutableBehaviorTests (4), AppliedCallsTests (4), AbstractArgumentsTests (4), StockDelegationTests (1)

**Defaults beyond literals** (114): ExecutableDefaultTests (8), ImmutableExecutableDefaultTests (2), DynamicDefaultsTests (9), InheritedManagedDefaultsTests (16), HookedManagedDefaultsTests (12), HookReferenceDefaultsTests (5), InheritedMixedDefaultsTests (3), InheritedReferenceDefaultsTests (1), MixedDefaultTests (2), DefaultTests (4), DefaultPrecedenceTests (2), CollectionDefaultTests (8), StockCollectionDefaultTests (2), ReferenceDefaultTests (8), StockReferenceDefaultTests (6), ProjectedDefaultsTests (2), TraitProviderDefaultsTests (2), ScalarHookTests (7), ConstantExpressionTests (3), ReflectionDefaultTests (4), SnapshotCollectionBehaviorTests (8)

**Builders and apply** (55): ArchiveBuilderTests (2), OrdinaryOwningBuilderTests (7), OrderedReferenceBuilderTests (3), StockReferenceBuilderTests (9), StockReciprocalBuilderTests (7), StockReciprocalListBuilderTests (4), StockManagedBuilderTests (4), StockOwnershipBuilderTests (3), StockScalarGetterBuilderTests (2), StockSeriesBuilderTests (2), StockSharedParentBuilderTests (5), StockSingularOwningBuilderTests (2), StockAbstractCustomerBuilderTests (2), ScalarCollectionApplyTests (3)

**Cursors and traversal** (34): ContainmentCursorTests (10), ContainmentCursorAncestryTests (4), ReferenceCursorTests (8), RetainedContainmentCursorTests (5), UnorderedCursorTests (4), StockCursorInsertTests (1), StockTraversalTests (2)

**External types, enums, codecs** (54): GeneratedExternalBindingTests (8), StockExternalBindingTests (8), StockRawExternalTypesTests (6), EnumBindingTests (4), GenericEnumBindingTests (6), ExternalSnapshotCodecTests (14), OpenScalarChannelTests (4), LegacyObjectTests (4)

**Generics, redeclaration, covariance** (93): GenericBoundTests (7), NominalBoundTests (6), GenericValueTests (4), GenericScalarTests (2), NumericGenericTests (2), GenericRoleMetadataTests (2), RedeclarationTests (5), CollectionRedeclarationTests (13), ExactRedeclarationsTests (4), UntypedListTests (10), ValueChoiceTests (7), CovarianceTests (5), ArrayDeclarationTests (4), DelegatedListsTests (4), ConcreteBasesTests (11), StockPropertyInheritanceTests (2), SecondaryScalarTests (4), MixedManifestTests (1)

**Real-model ports** (37): StockDevComTests (8), StockMiniClangTests (4), StockVFlowTests (3), StockNamedHostTests (5), StockUnparserMarkerTests (1), StockModelRegistryTests (3), StockCodeEntityTests (1), StockApplicationAliasTests (8), WarehousePromotionTests (4)

**Feature bundles** (14): FeatureBundleTests (14)

**List operations beyond the contract** (12): ListPredicateTests (3), OwningPredicateTests (2), ScalarListSortTests (4), StockOwningSortTests (3)

**Summaries and toString** (5): ModelSummaryTests (3), MutableToStringTests (1), ImmutableToStringTests (1)

**C# platform integration** (5): NativePropertyChangedTests (5)
