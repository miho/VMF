# Portable change journals

**Feature preview.** The current journal grammar uses envelope version `1`
in JSON, YAML, and XML. It includes every record described below. The wire
format can change during preview, even between builds with the same envelope
version. Readers reject other envelope versions, including `2` through `6`
from earlier previews. Once the format is stable, each supported version
will keep its own reader and writer so newer releases can read old journals.

`JournalRecorder` captures native VMF changes as immutable records. `VMFJournalCodec` writes and reads those records in JSON, YAML, and XML. `JournalReplayer` resolves IDs in a copy of a checkpoint and returns the replayed root.

```java
// Register stable wire type names and factories in a model-specific JournalBinding.
// Supply an ID for each object that survives saving and loading the checkpoint.
Document checkpoint = loadCheckpoint();
String checkpointId = verifiedCheckpointId();
Document working = checkpoint.vmf().content().deepCopy();
ChangeJournal journal;
try (JournalRecorder recorder = JournalRecorder.start(working, binding, checkpointId, 0)) {
    Changes changes = working.vmf().changes();
    changes.startTransaction();
    working.setTitle("Revised document");
    working.getItems().add(Item.newBuilder().withId("item-1").build());
    changes.publishTransaction();
    journal = recorder.finish();
}

String json = VMFJournalCodec.write(journal);
Document result = JournalReplayer.replay(loadCheckpoint(), binding, checkpointId, VMFJournalCodec.read(json));
```

The recorder owns one native recording session and replaces the previous native history at start. Publish groups with the root's `Changes` API. `finish()` publishes the pending group, stops native recording, and verifies the complete journal. Plain `close()` stops recording without publishing the pending group. It leaves live edits, individual changes, and previously published groups available. Call `finish()` explicitly to obtain the verified journal before leaving a try-with-resources scope. Edits omitted from explicit groups are stored in individual frames in native history order. A native transaction groups edits that have already run on the working model. The recorder replays each published transaction on a private graph and checks it against the live graph at that boundary. `finish()` rejects a recording if any checked state diverged, including the final state. Edit a child after reattaching it so its property changes are observable from the root. The replayer applies a journal on an independent graph and returns it after all frames succeed.

For a single edit group, `recordFrame` owns the native recording and transaction lifecycle. Its callback runs on the working model once. The helper returns after publication and final verification.

```java
ChangeJournal journal = JournalRecorder.recordFrame(working, binding, checkpointId, baseSequence, model -> {
    model.setTitle("Revised document");
    model.getItems().add(Item.newBuilder().withId("item-1").build());
});
```

Use model mutators inside the callback and leave starting, clearing and publishing the recording to the helper. Already-applied edits stay on the working graph after a failure. A throwing callback skips native publication of its pending group. A successful no-op returns an empty journal. Store the journal and publish the resulting application state at your own durability boundary. Replay and shadow verification invoke model mutation logic, so keep external side effects outside model setters and factories used by replay.

For a sequence of ticks, keep one recorder and its shadow graph open. Call `publishFrame()` after the edits for each group and let it manage native publication. It returns the new portable frame once the synchronous publication listeners have returned and the recorder has checked the live graph again. An empty result means the current group had no portable operations. `finish()` still checks native history and returns the complete journal.

```java
try (JournalRecorder recorder = JournalRecorder.start(working, binding, checkpointId, baseSequence)) {
    working.setTitle("First revision");
    Optional<ChangeJournal.Frame> first = recorder.publishFrame();

    working.setTitle("Second revision");
    Optional<ChangeJournal.Frame> second = recorder.publishFrame();

    ChangeJournal journal = recorder.finish();
}
```

Call from the model's writer after the edit methods return. Keep native transaction and recording calls out of this session's publication path, and use publication listeners to inspect the model. A verification failure or a throwing publication listener closes the recorder and leaves applied edits on the working graph. The application owns persistence and recovery.

A live observer can throw after the model has applied an edit. If that exception escapes
the editing callback of `recordFrame`, the helper returns no journal. With a writer-owned
session, the writer can catch the exception, inspect the graph, then publish the pending
changes through the usual verification step. Retrying a list edit can apply it twice.
For callback ordering during reparenting and nested edits, see
[observing model changes](observing-models.md).

The checkpoint identifier comes from the caller. A digest of the stored checkpoint bytes is one option. Verify the checkpoint identity when loading it. Replay checks that identifier, the model version, the root ID, and the old values at edited positions. The journal envelope version identifies the wire grammar. The separate model version identifies the model and binding used for replay. Unknown operation kinds are rejected.

Regenerating a model can change its mutation behavior. Older generated one-to-many `@Refers` lists could retain the former owner's entry after a list-side move. An older journal containing just the destination addition will remove that former entry when replayed with regenerated models. Keep the matching generated model and binding version for exact replay of affected archived histories, or migrate those histories explicitly. The fixed generator records the removal and addition in order using the existing journal format.

## Live edits and portable replay

`Changes.all()` exposes live `Change` objects for observation and undo on the original model. Each change refers to the actual affected object. `Change.apply()` takes that object, or the corresponding affected object for a plain value edit. VMF rejects model-valued changes applied to a different instance before assigning any values. Use a `JournalRecorder` from the starting checkpoint for replay on another graph, including edits to nested objects, containment changes, and references.

For a writable scalar on a nongeneric role, the corresponding object can be
another generated implementation of that role. When the value differs, the
target emits its own change. Undo on the original change still acts on its
original object.

The same role can receive recorded edits to scalar lists, maps, and sets.
The edit uses the receiving collection's current values and bounds. Undo
uses the collection that originally recorded the edit.

An ordinary model-list change whose recorded values are all null can also
apply its positions to a writable owner's ordinary list with the same model
property name, even when the element types differ. The receiving list
validates its own bounds and null rules, reports its own displaced values,
and updates its own inverse links.

`VMFJacksonModule` rejects serialization and deserialization of live `Change` objects. Save the `ChangeJournal` with `VMFJournalCodec.write()` and restore it with `VMFJournalCodec.read()`, as in the example above. The envelope preserves ordered transaction sequences and stores stable IDs for affected objects and reference payloads.

The deprecated `ModelDiff.apply(source, diff)` accepts changes bound to the original source. Pass that same source as the first argument. Use a change journal for recorded edits that need to replay from an independent checkpoint.

### Runtime behavior contract

A target can expose its own observer API. The observer trace, native history,
and journal replay must each satisfy their own ordering rules. A child
observer can make a nested scalar edit before the ancestor observer sees
the initiating edit. In the shared fixture, both ancestor histories record
the nested edit first. A native transaction in this order cannot undo both
edits to the same property because the last recorded edit no longer matches
the current value. Edits to separate properties can still be undone. Both
runtimes deliver the same live callback trace and replay the initiating edit
before the nested edit in a portable frame.

The first registered relay reached by a scalar notification uses the native
recording state at its turn. If an earlier callback starts recording and
the new relay joins after dispatch begins, it sees subsequent edits. An
already registered relay can also see the pending edit. A portable recorder
opened in that callback takes its checkpoint after the scalar edit was applied
and records only later scalar commits. Both live notifications still reach the
existing relay in their usual order.

Native history for scalar lists, maps, and unordered scalar sets follows the
recording relay. When an earlier observer edits another field, that edit
appears first in native history and the transaction can undo both changes.
[Collection callback order](observing-models.md#collection-callback-order)
defines when the recorder reaches each kind of collection relay.

For a reciprocal list edit, a recorder opened inside a source or inverse
callback starts from the graph visible in that callback. When both ends
are already linked, the checkpoint includes that link. The recorder
captures subsequent edits. Copying this checkpoint preserves repeated
list slots, null slots, and the identity membership on the opposite end.
The [paired reciprocal tests](../conformance/opposite-java/ReciprocalConformance.java)
exercise both callback positions and both traversal orders through V1
JSON, XML, and YAML.

1. **Mutation commit.** Use the point where a mutation takes effect to establish
   its order relative to any nested edits. For example, a source-list removal
   must precede an observer's edit to a sibling at its shifted index. Capture
   the operands against the state for that mutation. Preserve the order of
   the live notifications and the native history described in
   [observing model changes](observing-models.md).
2. **Notification completion.** Associate each captured edit with the
   corresponding native history entry. A new model object may be initialized
   during the notification that introduces it. Its creation definition includes
   initialization that occurs before those mutations become visible to the
   recording. Once an edit to that object is captured separately, the
   definition supplies the state required by that edit's precondition. An
   unrelated nested introduction leaves a pending definition open for further
   initialization. References to pending objects use their stable IDs.
3. **Frame preparation and verification.** Use the edits in the selected native
   group and put the portable operations in the order required to replay their
   effects and preconditions. Move a frozen definition earlier within the frame
   only when an earlier operation or definition requires it. Each new ID has
   one definition. Shared identities, opposite ends, and mutable reference
   cycles must survive replay. Publish a frame after the mutators return and
   verification against an independent replay graph succeeds. A capture or
   verification failure prevents a verified portable frame from being returned
   and leaves the live graph available to inspect.

The Java and C# runtimes use an internal capture component for these phases.
The recorder owns the recording session, maps model properties and IDs to
wire values, and verifies frames on a private graph. A backend can implement
the phases at different points in its mutation and delivery code as long as
the specified callback trace, target's native history, and portable replay results hold.

The Java and C# conformance gate exercises both producers and both readers
with generated models. These cases can be reused to qualify a new runtime.

| Exchange scenario | What the tests check |
| --- | --- |
| Nested scalar in the Java and C# `ObserverOrder` tests | Child and ancestor callbacks see the same sequence. The recording group determines native history order, and both runtimes replay the two writes in mutation order. |
| Scalar list and ordered scalar set followed by a callback's scalar write | The list enters native history after its recording group's callbacks. A later observer sees that entry, and V1 replays the list commit before the scalar write in either registration order. |
| Managed scalar map and unordered scalar set followed by a callback's scalar write | Native history follows the recording relay. With the prepared VCollections dependency, Java V1 retains the collection commit before callback edits even when the callback precedes the recording relay. |
| A direct managed map or unordered scalar set callback edits the same collection | C# and Java with prepared VCollections replay the original edit before the reentrant edit. The map retains the intermediate key value, and the set records both membership changes. |
| Membership journal `scalar-list` and positional journal nested list, slot, and membership | Native history preserves its established order. A portable frame retains the initiating edit before the nested edit that depends on it, including aliases and inverse links after replay. |
| Ordinary containment `transfer-source-*` | Source removal precedes the nested replacement that uses the shifted list. Destination addition restores ownership in the same frame. The cases cover local, recursive, and direct collection observers. |
| Ordinary containment unordered-set reentry | When an adoption callback moves the same child again, the portable frame replays the first adoption before its nested removal. Both observer scopes preserve native callbacks and end with the child held by the final owner. |
| Reciprocal source reassignment | When an observer on the former owner replaces a sibling at its shifted index, a portable frame removes the departing endpoint before that edit. Both setter-driven and list-driven cases cover local, recursive, and direct collection callbacks. |
| Creation journal `independent-observer-init`, `dependent-observer-init`, and `cross-*` | Independent and dependent introductions freeze at the appropriate boundary. Replay checks later writes, shared identity, and cycles. The `cross-*` cases exchange JSON, YAML, and XML. |
| Collection creation | Managed map and set entries retain their initial values, including present nulls and typed keys. |
| Scalar journals and `PublishFrame` tests | Frame grouping and sequence numbers survive exchange. Invalid replay preconditions are rejected. Writes and uncaught failures during verified publication prevent a frame from being returned. |

Run `./gradlew :vmf:verifyCSharpConformance` to generate and exchange these
fixtures. The task writes the documents under `conformance/build/interchange`.
The [multi-target design](multi-target-codegen.md#target-semantics) describes
how to qualify portable capabilities for another backend.

## Seek through history

The experimental `History` API lives in `vmf-runtime` and is marked deprecated while its contract is being evaluated. Pass it a checkpoint and its journal as Java objects. It copies the checkpoint on registration and returns a fresh model on each seek.

```java
History<Document> history = new History<>(binding);
history.addSegment(checkpointId, checkpoint, journal);
Document atSequenceOne = history.seek(1);
```

Supply segments from one timeline. Each covers its base sequence through its final transaction. `seek` uses the latest checkpoint whose segment covers the requested sequence and replays the required prefix. `Transaction.undo()` remains the live operation on a recorded transaction. Applications can supply checkpoints and journals entirely in memory or load them using the optional `vmf-jackson` module.

## External values

VMF graph copies share external values and external list elements. Journals and history accept external values when the binding supplies a codec for their exact runtime class. The standard registry includes `java.util.UUID` with the wire codec ID `java.uuid/v1`.

To add a value type, keep a registry on the binding and return it from `valueCodecs()`.

```java
private static final JournalValueCodecs VALUES = JournalValueCodecs.standard()
        .withImmutable("example.distance-mm/v1", Distance.class, Distance::toWire, Distance::fromWire);

@Override
public JournalValueCodecs valueCodecs() {
    return VALUES;
}
```

Registration asserts deep immutability, including everything reachable through the value. The encoder and decoder must be deterministic and side-effect free. The canonical Unicode text must round-trip to an equal value of the same exact class. Keep the codec ID and encoding stable for readers of existing journals and supply the same registry for recording, replay, and seeking. An immutable quantity can encode its magnitude and unit in its own codec. To use mutable input, convert it to an immutable value before storing it in the journaled model. Journal codecs run in the core runtime. Checkpoint serialization uses its own configuration, such as a Jackson module for the model's value types.

With an in-memory checkpoint from a generated model, Java and C# can record,
replay, and seek using the journal codec for the external value. Reading or
writing a checkpoint document uses the snapshot codec configured for that
value.

## Journal document formats

The default `write(journal)` and `read(document)` overloads use canonical JSON. Pass `VMFDocumentFormat.YAML` or `VMFDocumentFormat.XML` to select another format. The journal and its model checkpoint may use different formats.

```java
String yaml = VMFJournalCodec.write(journal, VMFDocumentFormat.YAML);
ChangeJournal fromYaml = VMFJournalCodec.read(yaml, VMFDocumentFormat.YAML);

String xml = VMFJournalCodec.write(journal, VMFDocumentFormat.XML);
ChangeJournal fromXml = VMFJournalCodec.read(xml, VMFDocumentFormat.XML);
```

YAML uses the same envelope keys, value types, and ordered sequences as JSON. Quote strings that resemble numbers, booleans, or null when editing a journal by hand. Journals reject YAML aliases.

XML uses a `vmf-journal` root in place of the `format` field. Lists use explicit wrappers such as `<transactions><transaction>…</transaction></transactions>`. Creation properties are ordered `property` elements with `name` and `values` children. Null text uses `<text null="true"/>`. Strings that XML cannot preserve verbatim, such as control characters or carriage returns, use an `encoding="base64"` attribute and UTF-8 bytes. The XML reader rejects document type declarations.

All three formats encode the same version 1 records and use the same validation rules.

## Version 1 JSON

```json
{
  "format": "vmf-journal",
  "version": 1,
  "model": { "id": "document", "version": 1 },
  "root": "doc-1",
  "base": { "checkpoint": "checkpoint-40", "sequence": 40 },
  "transactions": [
    {
      "sequence": 41,
      "operations": [
        {
          "kind": "SET",
          "target": "doc-1",
          "property": "title",
          "indices": [],
          "before": [{ "kind": "STRING", "text": "Draft" }],
          "after": [{ "kind": "STRING", "text": "Revised document" }],
          "created": []
        }
      ]
    }
  ]
}
```

- Transactions have consecutive sequence numbers. Operation order is the order of the array.
- `SET` takes one old and one new value. List operations pair strictly increasing zero-based `indices` with their values. `LIST_ADD` indices are in the resulting list. `LIST_REMOVE` and `LIST_SET` indices are in the preceding list.
- `REFERENCE` values store persistent IDs in `text`. Scalar tags are `NULL`, `STRING`, `BOOLEAN`, `BYTE`, `SHORT`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, and `CHAR`. `NULL` uses JSON null. Other values use JSON strings. Integers use signed decimal notation, `CHAR` uses an unsigned decimal UTF-16 code unit, and floating-point values use Java's exact hexadecimal spelling with `NaN`, `Infinity`, and `-Infinity` for special values.
- A `created` entry contains `id`, `type`, and `properties`, keyed by stable property name. Scalar initial properties have a singleton value array. List properties have an ordered value array. Creation records allocate new objects at their first introduction. Link values use IDs for both new and existing objects.

The journal supports forward replay of mutable generated VMF graphs with stable IDs, containment, internal references, and scalar or scalar-list values. References must resolve within the containment tree at each transaction boundary. The binding handles concrete factories and stable property keys.

## External values

An external value includes its registered codec ID. For example, a UUID journal value is `{"kind":"EXTERNAL","text":"980e13c6-84a0-49d6-821d-737269635aef","codec":"java.uuid/v1"}`. The replayer resolves the codec through the supplied binding. Codecs are identified by wire IDs supplied by the application. Replay rejects unknown codec IDs, noncanonical payloads, and values that cannot be assigned to the model property.

## Managed maps

A managed map edit stores the affected entries before and after one native map mutation. The wire key is a string and the value uses the built-in scalar tags. Keys and string values follow the journal’s Unicode validation, which rejects unpaired UTF-16 surrogates. Each key appears at most once in each entry array. A key present in both arrays is replaced. An addition only has an after entry and a removal only has a before entry. A present null has an explicit `NULL` value. The following operation replaces a present null and adds a second key in the same edit.

```json
{
  "kind": "MAP_EDIT",
  "target": "device-1",
  "property": "readings",
  "beforeEntries": [
    { "key": "temperature", "value": { "kind": "NULL", "text": null } }
  ],
  "afterEntries": [
    { "key": "temperature", "value": { "kind": "INT", "text": "21" } },
    { "key": "humidity", "value": { "kind": "INT", "text": "42" } }
  ]
}
```

Replay checks the old values and the absence of newly inserted keys before applying the edit. Every after value must match the declared map value type. Entry arrays preserve the order supplied by the recorded map event. When allocating a newly introduced object, the creation record carries a `mapProperties` array of records with `name` and `entries` fields. Each record contains the complete initial map, including an empty array for an empty managed map. Scalar and list properties continue to use the `properties` field.

Map edits identify entries by key. Native undo restores the previous mappings. For the Java insertion-ordered implementation, undoing a removal can append the restored key after entries that survived the removal. Forward journal replay from the recorded checkpoint preserves the insertion order of the edited map, and the recorder verifies this order against its replay shadow at transaction boundaries.

### Typed keys, unordered sets, and keyed model entries

A `SET_EDIT` names the target and property and gives two arrays of typed scalar values, `before` for removed members and `after` for added members. Membership must be unique and the arrays must be disjoint. Replay requires all removed members to be present and all added members to be absent. Set equivalence ignores iteration order. Ordered unique lists retain the positional list operations.

A `TYPED_MAP_EDIT` uses `beforeEntries` and `afterEntries`. Each entry contains a `typedKey` and a `value`, both in the journal's existing `kind` and `text` scalar format. For example, an integer key of 7 is encoded as `{"kind":"INT","text":"7"}`. Null map values remain distinct from missing keys. Typed floating-point keys retain the distinction between positive and negative zero. New object snapshots may carry `typedMapProperties` with named entry arrays and `setProperties` with named member arrays.

A keyed containment list persists its entry objects in list order. The lookup uses the recorded entry properties, so a key edit and an entry move remain ordinary property and list changes. Give each mutable entry its own stable journal ID and keep business keys separate from journal IDs when allowing key edits. Mutable reference targets must belong to the checkpoint containment tree at publication boundaries.

### Unique model references

Ordered reference edits use the existing list operations with target IDs and positions. Unordered edits use `REF_SET_EDIT`. Its `before` and `after` arrays contain the distinct IDs removed and added. Its `indices` array is empty. A newly attached target can carry creation records. Replay accepts an equivalent unordered checkpoint that enumerates members in a different order and rejects missing or already-present targets.

### Application type and property bindings in C#

C# bindings can use `WithObjectTypes(getType, create)` to give creation
records application type names and use an application factory. The registered C#
model types still come from the snapshot registry. Mutable factories must return
a fresh instance with the specified journal ID and type. An optional immutable
factory may register the object returned by its initializer under an application
ID before returning that same object. Checkpoint IDs must already survive the
ordinary checkpoint copy.

Pass `WithPropertyName(getPropertyName)` to supply the inbound half of a custom
property mapping. The incoming wire key must round-trip through the outbound
property key, as in Java. An inbound alias alone cannot migrate a journal. C#
bindings without an inbound mapping still resolve keys by scanning the model's
outbound keys. Journal bindings do not change snapshot type or field names.

### Immutable references

Use reference-mode checkpoints when a model shares immutable objects. The snapshot preserves aliases within the document. For journal replay, `JournalBinding.id(object)` must return the same ID after loading that checkpoint. Two different notes can have equal contents and still need distinct IDs. A persisted ID field or an application identity table can supply them.

The journal writes an immutable target as `{"kind":"IMMUTABLE_REFERENCE","text":"note-3"}`. A new target carries a creation record with its complete properties. Replay builds nested values first, then shares the resulting instance wherever that ID occurs.

If the existing `create(type, id)` factory returns an immutable prototype for the wire type, VMF obtains its generated builder automatically. A binding that keeps IDs outside the model can register the completed instance during construction.

```java
@Override
public VObject createImmutable(String type, String id, Function<Builder, VObject> initialize) {
    if (!type.equals("note")) throw new IllegalArgumentException("Unknown immutable type " + type);
    VObject note = initialize.apply(Note.newBuilder());
    idsByInstance.put(note, id); // an IdentityHashMap
    return note;
}
```
