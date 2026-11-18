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
 * M. Hoffer, C. Poliwoda, & W. Wittum. (2013). Visual reflection library:
 * A framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4), 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core.journal;

import eu.mihosoft.vmf.runtime.core.Transaction;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * In-memory navigation along one model's timeline. Each seek returns an independent model.
 * Live editing uses {@link Transaction#undo()} on the original model.
 *
 * @param <T> the model root type
 * @deprecated Experimental API. The history contract may change before stabilization.
 */
@Deprecated
public final class History<T extends VObject> {
    private final JournalBinding binding;
    private final NavigableMap<Long, Segment<T>> segments = new TreeMap<>();

    /**
     * Creates an empty history for the given model binding.
     * @param binding the mapping from journal identifiers to model objects and properties
     */
    public History(JournalBinding binding) {
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    /**
     * Registers a checkpoint at the journal's base sequence and takes an independent copy.
     * The caller identifies the checkpoint and supplies segments from the same timeline.
     * A journal with zero transactions covers just its checkpoint sequence.
     *
     * @param checkpointId the caller-verified checkpoint identifier
     * @param checkpoint the model at the journal's base sequence
     * @param journal the journal beginning at this checkpoint
     * @throws IllegalArgumentException if the base sequence is already registered or the checkpoint metadata differs
     */
    public synchronized void addSegment(String checkpointId, T checkpoint, ChangeJournal journal) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(journal, "journal");
        long baseSequence = journal.baseSequence();
        if (segments.containsKey(baseSequence)) {
            throw new IllegalArgumentException("History already has a checkpoint at sequence " + baseSequence);
        }
        if (!segments.isEmpty() && !segments.firstEntry().getValue().journal.root().equals(journal.root())) {
            throw new IllegalArgumentException("History segment has a different model root");
        }

        T copy = JournalReplayer.replay(checkpoint, binding, checkpointId, prefix(journal, 0));
        segments.put(baseSequence, new Segment<>(copy, journal));
    }

    /**
     * Returns a fresh model at the requested sequence. If multiple segments cover it, replay
     * begins at the latest covering checkpoint and runs through the requested transaction.
     *
     * @param sequence the checkpoint or transaction sequence to reconstruct
     * @return an independent model at that sequence
     * @throws IllegalArgumentException if no segment covers the sequence or replay fails validation
     */
    public synchronized T seek(long sequence) {
        Map.Entry<Long, Segment<T>> entry = segments.floorEntry(sequence);
        while (entry != null) {
            Segment<T> segment = entry.getValue();
            long count = sequence - entry.getKey();
            if (count <= segment.journal.transactions().size()) {
                ChangeJournal selected = prefix(segment.journal, (int) count);
                return JournalReplayer.replay(segment.checkpoint, binding, selected.checkpoint(), selected);
            }
            entry = segments.lowerEntry(entry.getKey());
        }
        throw new IllegalArgumentException("No history segment covers sequence " + sequence);
    }

    private static ChangeJournal prefix(ChangeJournal journal, int count) {
        return new ChangeJournal(journal.model(), journal.modelVersion(), journal.root(), journal.checkpoint(),
                journal.baseSequence(), journal.transactions().subList(0, count));
    }

    private static final class Segment<T extends VObject> {
        private final T checkpoint;
        private final ChangeJournal journal;

        private Segment(T checkpoint, ChangeJournal journal) {
            this.checkpoint = checkpoint;
            this.journal = journal;
        }
    }
}
