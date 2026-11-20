/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core.journal;

import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Transaction;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.*;

/** Captures a model's native change stream as an independent, portable forward journal. */
public final class JournalRecorder implements AutoCloseable {
    private final VObject root;
    private final VObject replayRoot;
    private final JournalReplayer verifier;
    private final JournalBinding binding;
    private final JournalSupport.Workspace workspace = new JournalSupport.Workspace();
    private final String model;
    private final int modelVersion;
    private final String checkpoint;
    private final long baseSequence;
    private final String rootId;
    private final Changes changes;
    private final List<Subscription> subscriptions = new ArrayList<>(2);
    private final Map<String, VObject> known;
    private final Map<VObject, String> identities = new IdentityHashMap<>();
    private final List<Change> captured = new ArrayList<>();
    private final List<Operation> operations = new ArrayList<>();
    private final List<List<Change>> published = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private int nextChange;
    private RuntimeException failure;
    private boolean closed;

    /**
     * Starts an exclusive recording on the model's current checkpoint state. Like {@link Changes#start()},
     * this replaces earlier native recording history. Use the model's {@link Changes} API to delimit
     * transactions while this recorder is open. Access the recorder and model from one writer.
     * Restarting or clearing the recording invalidates the journal.
     */
    public static JournalRecorder start(VObject root, JournalBinding binding, String checkpoint, long baseSequence) {
        return new JournalRecorder(root, binding, checkpoint, baseSequence);
    }

    private JournalRecorder(VObject root, JournalBinding binding, String checkpoint, long baseSequence) {
        this.root = Objects.requireNonNull(root, "root");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.model = JournalSupport.requireName(binding.modelId(), "model");
        this.modelVersion = binding.modelVersion();
        this.checkpoint = JournalSupport.requireName(checkpoint, "checkpoint");
        this.baseSequence = baseSequence;
        if (modelVersion < 1 || baseSequence < 0) {
            throw new IllegalArgumentException("Invalid model version or base sequence");
        }
        this.changes = root.vmf().changes();
        if (changes.isModelVersioningEnabled()) {
            throw new IllegalStateException("The model already has an active recording");
        }
        this.known = workspace.snapshot(root, binding);
        known.forEach((id, object) -> identities.put(object, id));
        this.rootId = identities.get(root);
        this.replayRoot = root.clone();
        workspace.verifyState(root, replayRoot, binding);
        this.verifier = new JournalReplayer(binding, known, workspace.snapshot(replayRoot, binding), workspace);
        try {
            changes.start();
            subscriptions.add(changes.all().addChangeListener(event -> {
                if (failure != null) return;
                if (event.wasRemoved()) {
                    failure = new IllegalStateException("Native change history was cleared during recording");
                    return;
                }
                for (Change change : event.added().elements()) {
                    try {
                        Operation operation = capture(change);
                        captured.add(change);
                        operations.add(operation);
                    } catch (RuntimeException ex) {
                        // Propagating through the native listener can interrupt bookkeeping for the edit.
                        failure = ex;
                        break;
                    }
                }
            }));
            subscriptions.add(changes.transactions().addChangeListener(event -> {
                if (failure != null) return;
                if (event.wasRemoved()) {
                    failure = new IllegalStateException("Native transaction history was cleared during recording");
                    return;
                }
                try {
                    for (Transaction transaction : event.added().elements()) captureBoundary(transaction);
                } catch (RuntimeException ex) {
                    // Leave native publication intact. The journal will be rejected at finish().
                    failure = new IllegalStateException("Published transaction state could not be reproduced", ex);
                }
            }));
        } catch (RuntimeException | Error ex) {
            try {
                try {
                    subscriptions.forEach(Subscription::unsubscribe);
                } finally {
                    changes.stop();
                }
            } catch (RuntimeException | Error stopFailure) {
                if (stopFailure != ex) ex.addSuppressed(stopFailure);
            }
            throw ex;
        }
    }

    private Operation capture(Change change) {
        VObject object = change.object();
        String name = change.propertyName();
        // The unnamed generated opposite can appear in the native stream despite having no property ID.
        if (JournalSupport.isContainer(object, name)) return null;
        var propertyChange = change.propertyChange();
        var listChange = change.listChange();
        if (listChange.isPresent() && !listChange.get().wasAdded() && !listChange.get().wasRemoved()) return null;
        if (!identities.containsKey(object)) {
            throw new IllegalArgumentException("Change target was not introduced before its first edit");
        }
        List<Creation> created = new ArrayList<>();
        String target = reference(object, created);
        String property = propertyKey(object, name);
        if (propertyChange.isPresent()) {
            return new Operation(Kind.SET, target, property, List.of(),
                    List.of(freeze(propertyChange.get().oldValue(), created)),
                    List.of(freeze(propertyChange.get().newValue(), created)), created);
        }

        var event = listChange.orElseThrow();
        Map<Integer, Value> before = indexed(event.removed(), created);
        Map<Integer, Value> after = indexed(event.added(), created);
        Kind kind = Kind.LIST_SET;
        if (before.isEmpty()) kind = Kind.LIST_ADD;
        else if (after.isEmpty()) kind = Kind.LIST_REMOVE;
        else if (!before.keySet().equals(after.keySet())) {
            throw new IllegalArgumentException("List replacement has different before and after indices");
        }
        return new Operation(kind, target, property, List.copyOf((before.isEmpty() ? after : before).keySet()),
                List.copyOf(before.values()), List.copyOf(after.values()), created);
    }

    private Map<Integer, Value> indexed(VListChange<Object> part, List<Creation> created) {
        int[] indices = part.indices();
        List<Object> values = part.elements();
        if (indices.length != values.size()) {
            throw new IllegalArgumentException("Native list change has mismatched indices and values");
        }
        if (indices.length == 0) return Map.of();
        if (indices.length == 1) {
            if (indices[0] < 0) {
                throw new IllegalArgumentException("Native list change has invalid or duplicate indices");
            }
            return Map.of(indices[0], freeze(values.get(0), created));
        }
        Map<Integer, Value> indexed = new TreeMap<>();
        for (int i = 0; i < indices.length; i++) {
            int position = indices[i];
            if (position < 0 || indexed.containsKey(position)) {
                throw new IllegalArgumentException("Native list change has invalid or duplicate indices");
            }
            indexed.put(position, freeze(values.get(i), created));
        }
        return indexed;
    }

    private Value freeze(Object value, List<Creation> created) {
        if (value instanceof VObject) {
            return new Value(ValueKind.REFERENCE, reference((VObject) value, created));
        }
        return JournalSupport.scalar(value, binding);
    }

    private String reference(VObject object, List<Creation> created) {
        String id = JournalSupport.requireName(binding.id(object), "object ID");
        String previous = identities.get(object);
        if (previous != null) {
            if (!previous.equals(id)) throw new IllegalArgumentException("Object ID changed during recording: " + previous);
            return id;
        }
        JournalSupport.mutable(object);
        if (known.putIfAbsent(id, object) != null) throw new IllegalArgumentException("Duplicate object ID: " + id);
        verifier.excludeLiveInstance(object);
        // Register the identity before capturing properties to allow forward and cyclic references.
        identities.put(object, id);
        Map<String, List<Value>> properties = new LinkedHashMap<>();
        for (Property property : ReflectImpl.readProperties(object.vmf().reflect())) {
            String name = property.getName();
            if (JournalSupport.isContainer(object, name)) continue;
            String key = propertyKey(object, name);
            Object value = property.get();
            if (property.getType().isListType()) {
                List<Value> items = new ArrayList<>();
                for (Object item : (List<?>) value) items.add(freeze(item, created));
                properties.put(key, items);
            } else {
                properties.put(key, List.of(freeze(value, created)));
            }
        }
        created.add(new Creation(id, binding.type(object), properties));
        return id;
    }

    private String propertyKey(VObject object, String name) {
        String key = JournalSupport.requireName(binding.propertyKey(object, name), "property key");
        if (!name.equals(binding.propertyName(object, key))) {
            throw new IllegalArgumentException("Property key does not resolve back to " + name);
        }
        return key;
    }

    /**
     * Stops recording, publishes any pending native transaction, and returns the captured journal.
     * @throws IllegalStateException if recording failed
     */
    public ChangeJournal finish() {
        if (closed) throw new IllegalStateException("Journal recorder is closed");
        close();
        if (failure != null) throw new IllegalStateException("Portable journal recording failed", failure);
        for (Map.Entry<VObject, String> entry : identities.entrySet()) {
            if (!entry.getValue().equals(binding.id(entry.getKey()))) {
                throw new IllegalStateException("Object ID changed during recording: " + entry.getValue());
            }
        }
        try (var liveIndex = workspace.index(root, binding)) {
            liveIndex.forEach((id, object) -> {
                if (known.get(id) != object) {
                    throw new IllegalStateException("Object entered the graph without a recorded change: " + id);
                }
            });
            verifyNativeHistory();
            try {
                if (nextChange < captured.size()) {
                    appendUngroupedUntil(captured.size());
                    workspace.verifyState(root, replayRoot, binding);
                } else {
                    // Replaying pending edits can call application code. Reuse the latest
                    // live scan only when history inspection was the sole intervening work.
                    workspace.verifyState(liveIndex, replayRoot, binding);
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Recorded changes could not reproduce the model state", ex);
            }
        }
        return new ChangeJournal(model, modelVersion, rootId, checkpoint, baseSequence, frames);
    }

    private void captureBoundary(Transaction transaction) {
        List<Change> group = transaction.changes();
        int end = captured.size();
        int start = end - group.size();
        if (group.isEmpty() || start < nextChange || !sameChanges(captured.subList(start, end), group)) {
            throw new IllegalStateException("Native transaction does not match recorded changes");
        }
        appendUngroupedUntil(start);
        appendFrame(start, end);
        nextChange = end;
        workspace.verifyState(root, replayRoot, binding);
        published.add(List.copyOf(group));
    }

    private void verifyNativeHistory() {
        if (!sameChanges(changes.all(), captured)) {
            throw new IllegalStateException("Native change history was replaced");
        }
        List<Transaction> transactions = changes.transactions();
        if (transactions.size() != published.size()) {
            throw new IllegalStateException("Native transaction history was replaced");
        }
        for (int i = 0; i < transactions.size(); i++) {
            if (!sameChanges(transactions.get(i).changes(), published.get(i))) {
                throw new IllegalStateException("Native transaction does not match recorded changes");
            }
        }
    }

    private static boolean sameChanges(List<Change> a, List<Change> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    private void appendUngroupedUntil(int end) {
        while (nextChange < end) {
            appendFrame(nextChange, nextChange + 1);
            nextChange++;
        }
    }

    private void appendFrame(int from, int to) {
        List<Operation> edits = new ArrayList<>();
        for (Operation operation : operations.subList(from, to)) {
            if (operation != null) edits.add(operation);
        }
        if (edits.isEmpty()) return;
        Frame frame = new Frame(Math.addExact(baseSequence, frames.size() + 1L), edits);
        verifier.applyFrame(frame, replayRoot);
        frames.add(frame);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (changes.isModelVersioningEnabled()) changes.stop();
        } finally {
            subscriptions.forEach(Subscription::unsubscribe);
        }
    }
}
