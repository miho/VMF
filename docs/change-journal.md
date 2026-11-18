# Portable change journals

`JournalRecorder` captures native VMF changes as immutable records. `VMFJournalCodec` writes and reads those records as versioned JSON. `JournalReplayer` resolves IDs in a copy of a checkpoint and returns the replayed root.

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

The recorder owns one native recording session and replaces the previous native history at start. Publish groups with the root's `Changes` API. `finish()` stops native recording and publishes its pending group. Edits omitted from explicit groups are stored individually in recorded order. A native transaction groups edits that have already run on the working model. The recorder replays each published transaction on a private graph and checks it against the live graph at that boundary. `finish()` rejects a recording if any checked state diverged, including the final state. Edit a child after reattaching it so its property changes are observable from the root. The replayer applies a journal on an independent graph and returns it after all frames succeed.

The checkpoint identifier comes from the caller. A digest of the stored checkpoint bytes is one option. Verify the checkpoint identity when loading it. Replay checks that identifier, the model version, the root ID, and the old values at edited positions. Use separate journal and model versions when evolving the format or the model. Unknown versions and operation kinds are rejected.

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
