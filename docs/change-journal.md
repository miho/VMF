# Portable change journals

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

The recorder owns one native recording session and replaces the previous native history at start. Publish groups with the root's `Changes` API. `finish()` publishes the pending group, stops native recording, and verifies the complete journal. Plain `close()` stops recording without publishing the pending group. It leaves live edits, individual changes, and previously published groups available. Call `finish()` explicitly to obtain the verified journal before leaving a try-with-resources scope. Edits omitted from explicit groups are stored individually in recorded order. A native transaction groups edits that have already run on the working model. The recorder replays each published transaction on a private graph and checks it against the live graph at that boundary. `finish()` rejects a recording if any checked state diverged, including the final state. Edit a child after reattaching it so its property changes are observable from the root. The replayer applies a journal on an independent graph and returns it after all frames succeed.

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

The checkpoint identifier comes from the caller. A digest of the stored checkpoint bytes is one option. Verify the checkpoint identity when loading it. Replay checks that identifier, the model version, the root ID, and the old values at edited positions. Use separate journal and model versions when evolving the format or the model. Unknown versions and operation kinds are rejected.

Regenerating a model can change its mutation behavior. Older generated one-to-many `@Refers` lists could retain the former owner's entry after a list-side move. An older journal containing just the destination addition will remove that former entry when replayed with regenerated models. Keep the matching generated model and binding version for exact replay of affected archived histories, or migrate those histories explicitly. The fixed generator records the removal and addition in order using the existing journal format.

## Live edits and portable replay

`Changes.all()` exposes live `Change` objects for observation and undo on the original model. Each change refers to the actual affected object. `Change.apply()` takes that object, or the corresponding affected object for a plain value edit. VMF rejects model-valued changes applied to a different instance before assigning any values. Use a `JournalRecorder` from the starting checkpoint for replay on another graph, including edits to nested objects, containment changes, and references.

`VMFJacksonModule` rejects serialization and deserialization of live `Change` objects. Save the `ChangeJournal` with `VMFJournalCodec.write()` and restore it with `VMFJournalCodec.read()`, as in the example above. The envelope preserves ordered transaction sequences and stores stable IDs for affected objects and reference payloads.

The deprecated `ModelDiff.apply(source, diff)` accepts changes bound to the original source. Pass that same source as the first argument. Use a change journal for recorded edits that need to replay from an independent checkpoint.

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

All three formats use the same wire versions and validation rules. The writer selects the oldest version capable of representing the recorded operations and object creations.

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

Version 1 supports forward replay of mutable generated VMF graphs with stable IDs, containment, internal references, and scalar or scalar-list values. References must resolve within the containment tree at each transaction boundary. The binding handles concrete factories and stable property keys.

## Version 2 JSON

Version 2 adds one value kind. For example, a UUID journal value is `{"kind":"EXTERNAL","text":"980e13c6-84a0-49d6-821d-737269635aef","codec":"java.uuid/v1"}`. The replayer resolves the codec through the supplied binding. Codecs are identified by wire IDs supplied by the application. Replay rejects unknown codec IDs, noncanonical payloads, and values that cannot be assigned to the model property. The writer uses version 1 for journals made up entirely of version 1 records. Readers that support version 2 also read version 1.

## Version 3 managed maps

A managed map edit stores the affected entries before and after one native map mutation. The wire key is a string and the value uses the scalar tags from version 1. Keys and string values follow the journal’s Unicode validation, which rejects unpaired UTF-16 surrogates. Each key appears at most once in each entry array. A key present in both arrays is replaced. An addition only has an after entry and a removal only has a before entry. A present null has an explicit `NULL` value. The following operation replaces a present null and adds a second key in the same edit.

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

Replay checks the old values and the absence of newly inserted keys before applying the edit. Every after value must match the declared map value type. Entry arrays preserve the order supplied by the recorded map event. When allocating a newly introduced object, the creation record carries a `mapProperties` array of records with `name` and `entries` fields. Each record contains the complete initial map, including an empty array for an empty managed map. Scalar and list properties continue to use the `properties` field. A journal that edits only scalar and list properties on existing objects can still use version 1 or 2.

Map edits identify entries by key. Native undo restores the previous mappings. For the Java insertion-ordered implementation, undoing a removal can append the restored key after entries that survived the removal. Forward journal replay from the recorded checkpoint preserves the insertion order of the edited map, and the recorder verifies this order against its replay shadow at transaction boundaries.

### Typed keys, unordered sets, and keyed model entries

Wire version 4 adds two operations. A `SET_EDIT` names the target and property and gives two arrays of typed scalar values, `before` for removed members and `after` for added members. Membership must be unique and the arrays must be disjoint. Replay requires all removed members to be present and all added members to be absent. Set equivalence ignores iteration order. Ordered unique lists retain the positional list operations.

A `TYPED_MAP_EDIT` uses `beforeEntries` and `afterEntries`. Each entry contains a `typedKey` and a `value`, both in the journal's existing `kind` and `text` scalar format. For example, an integer key of 7 is encoded as `{"kind":"INT","text":"7"}`. Null map values remain distinct from missing keys. Typed floating-point keys retain the distinction between positive and negative zero. New object snapshots may carry `typedMapProperties` with named entry arrays and `setProperties` with named member arrays. A journal uses the oldest version sufficient for all its operations and creations. Existing String-keyed maps continue to use version 3 records.

A keyed containment list persists its entry objects in list order. The lookup uses the recorded entry properties, so a key edit and an entry move remain ordinary property and list changes. Give each mutable entry its own stable journal ID and keep business keys separate from journal IDs when allowing key edits. Referenced model objects must belong to the checkpoint containment tree at publication boundaries. By-value persistence of immutable VMF model objects through Jackson is a separate capability from journal replay, which addresses mutable model objects by stable ID.

### Unique model references

Ordered reference edits use the existing list operations with target IDs and positions. Unordered edits use `REF_SET_EDIT` in wire version 5. Its `before` and `after` arrays contain the distinct IDs removed and added. Its `indices` array is empty. A newly attached target can carry creation records. Replay accepts an equivalent unordered checkpoint that enumerates members in a different order and rejects missing or already-present targets. A journal uses the earliest wire version required by its edits.
