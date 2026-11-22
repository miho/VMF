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
package eu.mihosoft.vmf.jackson.test.plugincomposition;

import eu.mihosoft.vmf.jackson.test.plugincomposition.calibration.Calibration;
import eu.mihosoft.vmf.jackson.test.plugincomposition.device.Device;
import eu.mihosoft.vmf.jackson.test.plugincomposition.telemetry.Telemetry;
import eu.mihosoft.vmf.runtime.core.FeatureAttachments;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureAttachmentsTest {
    @Test
    void keepsLiveFeaturesByOwnerIdentityAndDetachesOnlyTheRequestedInstance() {
        Device device = Device.newBuilder().withLabel("Stage").build();
        Device sameLabel = device.clone();
        assertEquals(device, sameLabel);
        Calibration calibration = Calibration.newInstance();
        Telemetry telemetry = Telemetry.newInstance();

        ModelRegistry registry = new ModelRegistry(List.of(
                Device.type().reflect().allTypes(),
                Calibration.type().reflect().allTypes(),
                Telemetry.type().reflect().allTypes()));
        FeatureAttachments attachments = new FeatureAttachments(registry);

        attachments.attach(device, calibration);
        attachments.attach(device, calibration);
        attachments.attach(device, telemetry);
        List<VObject> both = attachments.all(device);
        assertEquals(List.of(calibration, telemetry), both);
        assertTrue(attachments.all(sameLabel).isEmpty());

        Calibration found = attachments.find(device, Calibration.class).orElseThrow();
        assertSame(calibration, found);
        found.setOffsetMillimeters(4);
        assertEquals(4, calibration.getOffsetMillimeters());

        assertFalse(attachments.detach(device, calibration.clone()));
        assertTrue(attachments.detach(device, calibration));
        assertFalse(attachments.find(device, Calibration.class).isPresent());
        assertSame(telemetry, attachments.find(device, Telemetry.class).orElseThrow());
        assertTrue(attachments.detach(device, telemetry));
        assertTrue(attachments.all(device).isEmpty());
        assertEquals(2, both.size());
    }

    @Test
    void rejectsUnregisteredTypesAndDuplicateFeatureWithoutReplacingExistingAttachment() {
        Device device = Device.newInstance();
        Calibration calibration = Calibration.newInstance();
        Telemetry unregistered = Telemetry.newInstance();
        ModelRegistry registry = new ModelRegistry(List.of(
                Device.type().reflect().allTypes(),
                Calibration.type().reflect().allTypes()));
        FeatureAttachments attachments = new FeatureAttachments(registry);

        assertThrows(IllegalArgumentException.class, () -> attachments.attach(device, unregistered));
        assertThrows(IllegalArgumentException.class, () -> attachments.attach(unregistered, calibration));
        assertTrue(attachments.all(device).isEmpty());
        assertTrue(attachments.all(unregistered).isEmpty());

        attachments.attach(device, calibration);
        assertThrows(IllegalArgumentException.class,
                () -> attachments.attach(device, Calibration.newInstance()));
        assertSame(calibration, attachments.find(device, Calibration.class).orElseThrow());
    }
}
