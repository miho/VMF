/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmftest.performance.TreeNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Generic ordered tree edits and portable checkpoint recovery. */
final class JournalScenarios {
    private static final int SMALL_SIBLINGS = 4;
    private static final int LARGE_SIBLINGS = 48;
    private static final int SMALL_TAGS = 4;
    private static final int LARGE_TAGS = 256;
    private static final int SESSION_FRAMES = 8;
    private static final String CHECKPOINT = "tree-checkpoint";
    private static final String MODEL = "performance-tree";
    private static final String TYPE = "tree-node";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return ((TreeNode) object).getId(); }
        @Override public String type(VObject object) { return TYPE; }
        @Override public VObject create(String type, String id) {
            require(TYPE.equals(type), "Unknown journal type " + type);
            return node(id);
        }
    };

    private JournalScenarios() { }

    static List<Scenario> cases() {
        ReplayInput replay = new ReplayInput();
        qualifyCapture(LARGE_SIBLINGS, LARGE_TAGS);
        return List.of(
                new Scenario("journal.seal-frame.13-nodes", 4,
                        () -> new CaptureBatch(SMALL_SIBLINGS, SMALL_TAGS, 1)),
                new Scenario("journal.seal-frame.101-nodes.256-tags", 1,
                        () -> new CaptureBatch(LARGE_SIBLINGS, LARGE_TAGS, 1)),
                new Scenario("journal.seal-session.8-frames", 1,
                        () -> new CaptureBatch(SMALL_SIBLINGS, SMALL_TAGS, SESSION_FRAMES)),
                orderedCase("13-nodes.4-tags.1-frame", SMALL_SIBLINGS, SMALL_TAGS, 1),
                orderedCase("13-nodes.256-tags.1-frame", SMALL_SIBLINGS, LARGE_TAGS, 1),
                orderedCase("101-nodes.4-tags.1-frame", LARGE_SIBLINGS, SMALL_TAGS, 1),
                orderedCase("101-nodes.256-tags.1-frame", LARGE_SIBLINGS, LARGE_TAGS, 1),
                orderedCase("101-nodes.256-tags.8-frames", LARGE_SIBLINGS, LARGE_TAGS, 8),
                orderedCase("101-nodes.256-tags.32-frames", LARGE_SIBLINGS, LARGE_TAGS, 32),
                new Scenario("journal.replay.8-frames", 4, () -> replay.batch(null, null)),
                new Scenario("journal.replay-json.8-frames", 2, () -> replay.batch(replay.json, VMFJournalCodec.Format.JSON)),
                new Scenario("journal.replay-yaml.8-frames", 2, () -> replay.batch(replay.yaml, VMFJournalCodec.Format.YAML)),
                new Scenario("journal.replay-xml.8-frames", 2, () -> replay.batch(replay.xml, VMFJournalCodec.Format.XML)));
    }

    private static Scenario orderedCase(String size, int siblings, int tags, int frames) {
        return new Scenario("journal.ordered." + size, 1, () -> new CaptureBatch(siblings, tags, frames, true));
    }

    private static TreeNode node(String id) {
        TreeNode node = TreeNode.newInstance();
        node.setId(id);
        node.setLabel("node " + id);
        return node;
    }

    private static final class Work {
        final TreeNode root = node("root");
        final TreeNode left = node("left");
        final TreeNode right = node("right");
        final TreeNode moving = node("moving");
        final int tags;

        Work(int siblings, int tags) {
            this.tags = tags;
            root.getChildren().addAll(List.of(left, right));
            for (int i = 0; i < siblings; i++) {
                left.getChildren().add(node("left-" + i));
                right.getChildren().add(node("right-" + i));
            }
            moving.getChildren().add(node("descendant"));
            left.getChildren().add(1, moving);
            moving.setLink(left);
            for (int i = 0; i < tags; i++) moving.getTags().add("tag-" + i);
        }

        void step() {
            int revision = root.getValue() + 1;
            boolean moveRight = (revision & 1) != 0;
            TreeNode destination = moveRight ? right : left;
            destination.getChildren().add(1, moving);
            moving.setLink(destination);
            if (moveRight) {
                moving.getTags().add("marked");
            } else {
                moving.getTags().remove(tags);
            }
            moving.setValue(revision);
            moving.setWeight(revision * 0.125);
            root.setValue(revision);
        }
    }

    private static ChangeJournal record(Work work, int frames) {
        return record(work, frames, null);
    }

    private static ChangeJournal record(Work work, int frames, long[] tickNanos) {
        try (JournalRecorder recorder = JournalRecorder.start(work.root, BINDING, CHECKPOINT, work.root.getValue())) {
            var changes = work.root.vmf().changes();
            for (int i = 0; i < frames; i++) {
                long start = tickNanos == null ? 0L : System.nanoTime();
                changes.startTransaction();
                work.step();
                if (tickNanos != null) {
                    work.moving.setLabel("transient");
                    work.moving.setLabel("node moving");
                }
                changes.publishTransaction();
                if (tickNanos != null) tickNanos[i] = System.nanoTime() - start;
            }
            return recorder.finish();
        }
    }

    private static final class CaptureBatch implements Scenario.Batch {
        private final Work work;
        private final int siblings;
        private final int frames;
        private final long[] tickNanos;
        private ChangeJournal journal;

        CaptureBatch(int siblings, int tags, int frames) {
            this(siblings, tags, frames, false);
        }

        CaptureBatch(int siblings, int tags, int frames, boolean ordered) {
            this.work = new Work(siblings, tags);
            this.siblings = siblings;
            this.frames = frames;
            this.tickNanos = ordered ? new long[frames] : null;
        }

        @Override public void run(int iterations) {
            for (int i = 0; i < iterations; i++) journal = record(work, frames, tickNanos);
        }

        @Override public void verify(int iterations) {
            int revision = iterations * frames;
            require(journal.baseSequence() == revision - frames && journal.transactions().size() == frames,
                    "Unexpected journal range");
            verifyState(work.root, siblings, work.tags, revision);
            if (tickNanos != null) verifyTransientWrites(journal);
        }

        @Override public long[] tickNanos() { return tickNanos; }
    }

    private static void verifyTransientWrites(ChangeJournal journal) {
        for (var frame : journal.transactions()) {
            var operations = frame.operations();
            int size = operations.size();
            require(size >= 2, "Missing ordered writes");
            require(isLabelWrite(operations.get(size - 2), "node moving", "transient")
                            && isLabelWrite(operations.get(size - 1), "transient", "node moving"),
                    "Intermediate label writes were lost or reordered");
        }
    }

    private static boolean isLabelWrite(ChangeJournal.Operation operation, String before, String after) {
        return operation.kind() == ChangeJournal.Kind.SET
                && "moving".equals(operation.target()) && "label".equals(operation.property())
                && before.equals(operation.before().get(0).text())
                && after.equals(operation.after().get(0).text());
    }

    private static final class ReplayInput {
        final TreeNode checkpoint = new Work(SMALL_SIBLINGS, SMALL_TAGS).root;
        final ChangeJournal journal = record(new Work(SMALL_SIBLINGS, SMALL_TAGS), SESSION_FRAMES);
        final byte[] checkpointJson;
        final String json;
        final String yaml;
        final String xml;

        ReplayInput() {
            try {
                checkpointJson = JSON.writeValueAsBytes(checkpoint);
                json = VMFJournalCodec.write(journal);
                yaml = VMFJournalCodec.write(journal, VMFJournalCodec.Format.YAML);
                xml = VMFJournalCodec.write(journal, VMFJournalCodec.Format.XML);
                verifyPrefixes(checkpoint, journal, SMALL_SIBLINGS, SMALL_TAGS);
                qualifyEncoding(json, VMFJournalCodec.Format.JSON);
                qualifyEncoding(yaml, VMFJournalCodec.Format.YAML);
                qualifyEncoding(xml, VMFJournalCodec.Format.XML);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        void qualifyEncoding(String encoded, VMFJournalCodec.Format format) throws IOException {
            TreeNode loaded = JSON.readValue(checkpointJson, TreeNode.class);
            require(loaded != checkpoint, "Checkpoint loading did not produce a fresh instance");
            verifyPrefixes(loaded, VMFJournalCodec.read(encoded, format), SMALL_SIBLINGS, SMALL_TAGS);
        }

        Scenario.Batch batch(String encoded, VMFJournalCodec.Format format) {
            return new Scenario.Batch() {
                private TreeNode source;
                private TreeNode replayed;

                @Override public void run(int iterations) {
                    if (encoded == null) {
                        source = checkpoint;
                        for (int i = 0; i < iterations; i++) {
                            replayed = JournalReplayer.replay(source, BINDING, CHECKPOINT, journal);
                        }
                        return;
                    }
                    try {
                        for (int i = 0; i < iterations; i++) {
                            source = JSON.readValue(checkpointJson, TreeNode.class);
                            replayed = JournalReplayer.replay(source, BINDING, CHECKPOINT,
                                    VMFJournalCodec.read(encoded, format));
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }

                @Override public void verify(int iterations) {
                    require(replayed != source, "Replay reused the source graph");
                    verifyState(replayed, SMALL_SIBLINGS, SMALL_TAGS, SESSION_FRAMES);
                    verifyState(source, SMALL_SIBLINGS, SMALL_TAGS, 0);
                }
            };
        }
    }

    private static void qualifyCapture(int siblings, int tags) {
        var working = new Work(siblings, tags);
        for (int i = 0; i < 2; i++) {
            var checkpoint = working.root.clone();
            verifyPrefixes(checkpoint, record(working, 1), siblings, tags);
        }
    }

    private static void verifyPrefixes(TreeNode checkpoint, ChangeJournal journal, int siblings, int tags) {
        int base = Math.toIntExact(journal.baseSequence());
        verifyAllProperties(checkpoint, siblings, tags, base);
        for (int count = 1; count <= journal.transactions().size(); count++) {
            var prefix = new ChangeJournal(journal.model(), journal.modelVersion(), journal.root(), journal.checkpoint(), base,
                    journal.transactions().subList(0, count));
            var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, prefix);
            require(replayed != checkpoint, "Replay reused the checkpoint");
            verifyAllProperties(replayed, siblings, tags, base + count);
        }
        verifyAllProperties(checkpoint, siblings, tags, base);
    }

    private static void verifyAllProperties(TreeNode root, int siblings, int tags, int revision) {
        verifyState(root, siblings, tags, revision);
        TreeNode owner = root.getChildren().get(revision & 1);
        TreeNode moving = owner.getChildren().get(1);
        verifyUnchangedProperties(root, root, owner, moving);
        for (int i = 0; i < tags; i++) {
            require(("tag-" + i).equals(moving.getTags().get(i)), "Preexisting tag changed");
        }
    }

    private static void verifyUnchangedProperties(TreeNode node, TreeNode root, TreeNode owner, TreeNode moving) {
        require(("node " + node.getId()).equals(node.getLabel()), "Label changed");
        if (node != moving) {
            require(node.getTags().isEmpty() && node.getLink() == null && node.getWeight() == 0.0,
                    "Unedited property changed");
            if (node != root) require(node.getValue() == 0, "Unedited value changed");
        }
        require(node == owner || node.getLinkedFrom() == null, "Unexpected inverse link");
        if (node != root && node.getParent() != root && node != moving) {
            require(node.getChildren().isEmpty(), "Leaf gained a child");
        }
        for (TreeNode child : node.getChildren()) verifyUnchangedProperties(child, root, owner, moving);
    }

    private static void verifyState(TreeNode root, int siblings, int tags, int revision) {
        require("root".equals(root.getId()) && root.getValue() == revision && root.getParent() == null,
                "Unexpected root or revision");
        require(root.getChildren().size() == 2, "Unexpected branch count");
        TreeNode left = root.getChildren().get(0);
        TreeNode right = root.getChildren().get(1);
        require("left".equals(left.getId()) && "right".equals(right.getId())
                && left.getParent() == root && right.getParent() == root, "Unexpected branch ordering or ownership");
        boolean moveRight = (revision & 1) != 0;
        TreeNode owner = moveRight ? right : left;
        TreeNode other = moveRight ? left : right;
        TreeNode moving = owner.getChildren().get(1);
        require("moving".equals(moving.getId()) && moving.getParent() == owner
                && moving.getValue() == revision && moving.getWeight() == revision * 0.125,
                "Unexpected moving node or scalar values");
        require(moving.getLink() == owner && owner.getLinkedFrom() == moving && other.getLinkedFrom() == null,
                "Unexpected cross-reference or opposite relationship");
        require(moving.getChildren().size() == 1
                && "descendant".equals(moving.getChildren().get(0).getId())
                && moving.getChildren().get(0).getParent() == moving, "Moved subtree was lost");
        require(moving.getTags().size() == tags + (moveRight ? 1 : 0), "Unexpected tag count");
        require(!moveRight || "marked".equals(moving.getTags().get(tags)), "Missing appended tag");
        require(("tag-" + (tags - 1)).equals(moving.getTags().get(tags - 1)), "Preexisting tag order changed");
        verifyBranch(left, siblings, !moveRight);
        verifyBranch(right, siblings, moveRight);
    }

    private static void verifyBranch(TreeNode branch, int siblings, boolean hasMovedChild) {
        require(branch.getChildren().size() == siblings + (hasMovedChild ? 1 : 0), "Unexpected child count");
        for (int i = 0; i < siblings; i++) {
            TreeNode child = branch.getChildren().get(i + (i > 0 && hasMovedChild ? 1 : 0));
            require(child.getParent() == branch && (branch.getId() + "-" + i).equals(child.getId()),
                    "Unexpected child order or ownership");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
