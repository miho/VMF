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
 * A framework for declarative GUI programming. InComputing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.history;

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.History;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

@SuppressWarnings("deprecation")
public class HistoryTest {
    private static final JournalBinding BINDING = new RobotBinding();

    private static class RobotBinding implements JournalBinding {
        @Override
        public String modelId() {
            return "test.robot-arm";
        }

        @Override
        public int modelVersion() {
            return 1;
        }

        @Override
        public String id(VObject object) {
            if (object instanceof RobotArm) return ((RobotArm) object).getId();
            if (object instanceof Joint) return ((Joint) object).getId();
            throw new IllegalArgumentException("Unknown robot model object");
        }

        @Override
        public String type(VObject object) {
            if (object instanceof RobotArm) return "arm";
            if (object instanceof Joint) return "joint";
            throw new IllegalArgumentException("Unknown robot model object");
        }

        @Override
        public VObject create(String type, String id) {
            switch (type) {
                case "arm": return RobotArm.newBuilder().withId(id).build();
                case "joint": return Joint.newBuilder().withId(id).build();
                default: throw new IllegalArgumentException("Unknown robot model type " + type);
            }
        }
    }

    @Test
    public void seeksBackwardWithoutAliasingCheckpointsOrResults() {
        RobotArm checkpoint = arm(100);
        ChangeJournal journal = record("initial", checkpoint, 10, List.of(
                arm -> arm.getJoints().get(0).setAngleDegrees(45),
                arm -> {
                    arm.setReachMm(150);
                    arm.getJoints().add(joint("elbow", 30));
                }));
        History<RobotArm> history = new History<>(BINDING);
        history.addSegment("initial", checkpoint, journal);

        RobotArm end = history.seek(12);
        assertEquals(150, end.getReachMm());
        assertEquals(2, end.getJoints().size());
        assertEquals("elbow", end.getJoints().get(1).getId());
        assertSame(end, end.getJoints().get(1).getArm());

        RobotArm middle = history.seek(11);
        assertEquals(100, middle.getReachMm());
        assertEquals(1, middle.getJoints().size());
        assertEquals(45, middle.getJoints().get(0).getAngleDegrees());
        assertNotSame(checkpoint.getJoints().get(0), middle.getJoints().get(0));
        assertNotSame(end.getJoints().get(0), middle.getJoints().get(0));
        assertSame(middle, middle.getJoints().get(0).getArm());

        checkpoint.setReachMm(999);
        checkpoint.getJoints().get(0).setAngleDegrees(99);
        middle.getJoints().get(0).setAngleDegrees(90);
        end.getJoints().clear();
        RobotArm start = history.seek(10);
        assertNotSame(checkpoint, start);
        assertEquals(100, start.getReachMm());
        assertEquals(10, start.getJoints().get(0).getAngleDegrees());
        assertEquals(45, history.seek(11).getJoints().get(0).getAngleDegrees());
        assertEquals(2, history.seek(12).getJoints().size());
    }

    @Test
    public void choosesTheLatestCheckpointThatCoversTheSequence() {
        RobotArm earlier = arm(100);
        ChangeJournal longer = record("earlier", earlier, 10, List.of(
                arm -> arm.setReachMm(110),
                arm -> {
                    arm.setReachMm(120);
                    arm.getJoints().add(joint("elbow", 30));
                },
                arm -> arm.setReachMm(130)));
        RobotArm later = arm(120);
        later.getJoints().add(joint("elbow", 30));
        ChangeJournal checkpointOnly = record("later", later, 12, List.of());
        List<String> createdIds = new ArrayList<>();
        RobotBinding trackingBinding = new RobotBinding() {
            @Override
            public VObject create(String type, String id) {
                createdIds.add(id);
                return super.create(type, id);
            }
        };
        History<RobotArm> history = new History<>(trackingBinding);
        history.addSegment("later", later, checkpointOnly);
        history.addSegment("earlier", earlier, longer);

        RobotArm atCheckpoint = history.seek(12);
        assertEquals(120, atCheckpoint.getReachMm());
        assertNotSame(later, atCheckpoint);
        assertEquals("elbow", atCheckpoint.getJoints().get(1).getId());
        assertEquals(List.of(), createdIds);
        assertEquals(110, history.seek(11).getReachMm());
        assertEquals(130, history.seek(13).getReachMm());
        assertEquals(List.of("elbow"), createdIds);
    }

    @Test
    public void rejectsUnknownSequenceAndInvalidSegmentsWithoutReplacingExistingHistory() {
        RobotArm checkpoint = arm(100);
        ChangeJournal journal = record("initial", checkpoint, 10, List.of(arm -> arm.setReachMm(110)));
        History<RobotArm> history = new History<>(BINDING);
        assertThrows(IllegalArgumentException.class, () -> history.seek(10));
        assertThrows(IllegalArgumentException.class, () -> history.addSegment("different", checkpoint, journal));
        RobotArm wrongRoot = checkpoint.clone();
        wrongRoot.setId("different-arm");
        assertThrows(IllegalArgumentException.class, () -> history.addSegment("initial", wrongRoot, journal));

        history.addSegment("initial", checkpoint, journal);
        RobotArm duplicateBase = arm(200);
        ChangeJournal duplicate = record("duplicate", duplicateBase, 10, List.of());
        assertThrows(IllegalArgumentException.class, () -> history.addSegment("duplicate", duplicateBase, duplicate));
        assertThrows(IllegalArgumentException.class, () -> history.seek(9));
        assertThrows(IllegalArgumentException.class, () -> history.seek(12));
        assertEquals(100, history.seek(10).getReachMm());
        assertEquals(110, history.seek(11).getReachMm());
    }

    private static RobotArm arm(int reachMm) {
        RobotArm arm = RobotArm.newBuilder().withId("arm-1").withReachMm(reachMm).build();
        arm.getJoints().add(joint("shoulder", 10));
        return arm;
    }

    private static Joint joint(String id, int angleDegrees) {
        return Joint.newBuilder().withId(id).withAngleDegrees(angleDegrees).build();
    }

    private static ChangeJournal record(String checkpointId, RobotArm checkpoint, long sequence,
                                        List<Consumer<RobotArm>> edits) {
        RobotArm work = checkpoint.clone();
        try (JournalRecorder recorder = JournalRecorder.start(work, BINDING, checkpointId, sequence)) {
            var changes = work.vmf().changes();
            for (Consumer<RobotArm> edit : edits) {
                changes.startTransaction();
                edit.accept(work);
                changes.publishTransaction();
            }
            return recorder.finish();
        }
    }
}
