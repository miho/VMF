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
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * A framework for declarative GUI programming. InComputing and Visualization in Science, 2013, 16(4),
 * 181â€“192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.list_set_replay.impl;

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmftest.list_set_replay.ListReplayModel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class JournalTextVerificationTest {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() {
            return "test.text-list";
        }

        @Override public int modelVersion() {
            return 1;
        }

        @Override public String id(VObject object) {
            return "root";
        }

        @Override public String type(VObject object) {
            return "text-list";
        }

        @Override public VObject create(String type, String id) {
            return ListReplayModel.newInstance();
        }
    };

    @Test
    public void finishValidatesAReplacementAtAPreviouslyScannedPosition() {
        var model = ListReplayModel.newInstance();
        for (int i = 0; i < 64; i++) model.getValues().add("value-" + i + " \uD83D\uDE00");
        var changes = model.vmf().changes();

        try (var recorder = JournalRecorder.start(model, BINDING, "initial", 0)) {
            changes.startTransaction();
            model.getValues().set(32, "published \uD83D\uDE00");
            changes.publishTransaction();
            assertEquals(1, changes.transactions().size());

            // Bypass native notifications so the finish-time graph check must see the replacement.
            var backing = ((ListReplayModelImpl) model).__vmf_get_prop_values__vmf_RawList();
            backing.set(32, "\uD800");

            var error = assertThrows(IllegalArgumentException.class, recorder::finish);
            assertEquals("Text contains an unpaired surrogate", error.getMessage());
            assertFalse(changes.isModelVersioningEnabled());
        }
    }
}
