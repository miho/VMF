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

The recorder owns one native recording session and replaces the previous native history at start. Publish groups with the root's `Changes` API. `finish()` stops native recording and publishes its pending group. Edits omitted from explicit groups are stored individually in recorded order. A native transaction groups edits that have already run on the working model. The replayer applies the journal on an independent copy and returns it after all frames succeed. The recorder also checks that its journal reproduces the final state from the saved starting graph. It rejects a recording if the native stream missed an edit, for example a property edit made while a child was detached.

The checkpoint identifier comes from the caller. A digest of the stored checkpoint bytes is one option. Verify the checkpoint identity when loading it. Replay checks that identifier, the model version, the root ID, and the old values at edited positions. Use separate journal and model versions when evolving the format or the model. Unknown versions and operation kinds are rejected.

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

Version 1 supports forward replay of mutable generated VMF graphs with stable IDs, containment, internal references, and scalar or scalar-list values. References must resolve within the containment tree at each transaction boundary. The binding handles concrete factories and stable property keys. Changes to mutable external values in place, immutable VMF payloads, portable undo, and crash-safe storage need separate support.
