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
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.jackson.test.plugincomposition;

import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFFeatureCodec;
import eu.mihosoft.vmf.jackson.test.plugincomposition.calibration.Calibration;
import eu.mihosoft.vmf.jackson.test.plugincomposition.calibration.CalibrationPlugin;
import eu.mihosoft.vmf.jackson.test.plugincomposition.device.Device;
import eu.mihosoft.vmf.jackson.test.plugincomposition.device.SwitchForDeviceModel;
import eu.mihosoft.vmf.jackson.test.plugincomposition.telemetry.Telemetry;
import eu.mihosoft.vmf.jackson.test.plugincomposition.telemetry.TelemetryPlugin;
import eu.mihosoft.vmf.runtime.core.FeatureAttachments;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Reflect;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class PluginCompositionExampleTest {
    private static final List<String> FILE_EXTENSIONS = List.of("json", "yaml", "yml", "xml");

    @Test
    void addsCalibrationAndTelemetryToOneDevice() {
        ModelRegistry registry = modelRegistry();
        assertEquals(List.of(Device.type(), Calibration.type(), Telemetry.type()), registry.allTypes());

        Device device = Device.newBuilder().withLabel("Positioning stage").build();
        FeatureAttachments features = new FeatureAttachments(registry);
        CalibrationPlugin.attachTo(device, features);
        TelemetryPlugin.attachTo(device, features);

        Calibration calibration = features.find(device, Calibration.class).orElseThrow();
        Telemetry telemetry = features.find(device, Telemetry.class).orElseThrow();
        assertEquals(2, calibration.getOffsetMillimeters());
        assertEquals(50, telemetry.getSampleRateHz());

        Map<String, Map<String, Object>> valuesByType = new LinkedHashMap<>();
        for (VObject feature : features.all(device)) {
            Reflect reflect = feature.vmf().reflect();
            Map<String, Object> propertyValues = new LinkedHashMap<>();
            for (Property property : reflect.properties()) {
                propertyValues.put(property.getName(), property.get());
            }
            valuesByType.put(reflect.type().getName(), propertyValues);
        }
        assertEquals(Map.of(
                Calibration.type().getName(), Map.of("offsetMillimeters", 2),
                Telemetry.type().getName(), Map.of("sampleRateHz", 50)), valuesByType);

        SwitchForDeviceModel<String> labelOf = new SwitchForDeviceModel<>() {
            @Override
            public String caseDevice(Device object) {
                return object.getLabel();
            }
        };
        assertEquals("Positioning stage", labelOf.doSwitch(device));
        assertNull(labelOf.doSwitch(calibration));
        assertNull(labelOf.doSwitch(telemetry));
    }

    @TestFactory
    Stream<DynamicTest> savesAndReloadsDeviceWithBothFeatures(@TempDir Path directory) {
        return FILE_EXTENSIONS.stream().map(extension -> dynamicTest(extension,
                () -> roundTripDevice(directory.resolve("device." + extension), extension)));
    }

    private void roundTripDevice(Path file, String extension) throws IOException {
        ModelRegistry registry = modelRegistry();
        Device device = Device.newBuilder().withLabel("Positioning stage").build();
        FeatureAttachments features = new FeatureAttachments(registry);
        CalibrationPlugin.attachTo(device, features);
        TelemetryPlugin.attachTo(device, features);

        Calibration calibration = features.find(device, Calibration.class).orElseThrow();
        Telemetry telemetry = features.find(device, Telemetry.class).orElseThrow();
        calibration.setOffsetMillimeters(-3);
        telemetry.setSampleRateHz(120);

        new VMFFeatureCodec(registry).save(file, device, features);
        assertNativeFormat(file, extension);

        var loaded = new VMFFeatureCodec(modelRegistry()).load(file, Device.class);
        Device restored = loaded.owner();
        FeatureAttachments restoredFeatures = loaded.attachments();

        assertNotSame(device, restored);
        assertEquals("Positioning stage", restored.getLabel());
        Calibration restoredCalibration = restoredFeatures.find(restored, Calibration.class).orElseThrow();
        Telemetry restoredTelemetry = restoredFeatures.find(restored, Telemetry.class).orElseThrow();
        assertNotSame(calibration, restoredCalibration);
        assertNotSame(telemetry, restoredTelemetry);
        assertEquals(-3, restoredCalibration.getOffsetMillimeters());
        assertEquals(120, restoredTelemetry.getSampleRateHz());
        assertEquals(List.of(restoredCalibration, restoredTelemetry), restoredFeatures.all(restored));
    }

    @TestFactory
    Stream<DynamicTest> reportsMissingPluginTypeOnLoad(@TempDir Path directory) {
        return FILE_EXTENSIONS.stream().map(extension -> dynamicTest(extension,
                () -> reportsMissingPluginType(directory.resolve("missing-plugin." + extension))));
    }

    private void reportsMissingPluginType(Path file) throws IOException {
        ModelRegistry registry = modelRegistry();
        Device device = Device.newInstance();
        FeatureAttachments features = new FeatureAttachments(registry);
        TelemetryPlugin.attachTo(device, features);

        new VMFFeatureCodec(registry).save(file, device, features);

        ModelRegistry withoutTelemetry = new ModelRegistry(List.of(
                Device.type().reflect().allTypes(), CalibrationPlugin.types()));
        VMFFeatureCodec codec = new VMFFeatureCodec(withoutTelemetry);
        IOException failure = assertThrows(IOException.class, () -> codec.load(file, Device.class));
        assertTrue(failure.getMessage().contains(Telemetry.class.getName()), failure::getMessage);
    }

    @Test
    void infersCaseInsensitiveFormatsAndDefaultsExtensionlessFilesToJson(@TempDir Path directory) throws IOException {
        ModelRegistry registry = modelRegistry();
        Device device = Device.newBuilder().withLabel("Positioning stage").build();
        FeatureAttachments features = new FeatureAttachments(registry);
        VMFFeatureCodec codec = new VMFFeatureCodec(registry);

        Path noExtension = directory.resolve("device");
        codec.save(noExtension, device, features);
        assertNativeFormat(noExtension, "json");
        assertEquals(device.getLabel(), codec.load(noExtension, Device.class).owner().getLabel());

        Path upperCaseAlias = directory.resolve("device.YML");
        codec.save(upperCaseAlias, device, features);
        assertNativeFormat(upperCaseAlias, "yml");
        assertEquals(device.getLabel(), codec.load(upperCaseAlias, Device.class).owner().getLabel());
    }

    @Test
    void requiresExplicitFormatForUnknownExtension(@TempDir Path directory) throws IOException {
        ModelRegistry registry = modelRegistry();
        Device device = Device.newBuilder().withLabel("Positioning stage").build();
        FeatureAttachments features = new FeatureAttachments(registry);
        VMFFeatureCodec codec = new VMFFeatureCodec(registry);

        Path path = directory.resolve("device.snapshot");
        IOException saveError = assertThrows(IOException.class, () -> codec.save(path, device, features));
        assertTrue(saveError.getMessage().toLowerCase(Locale.ROOT).contains("explicit"), saveError::getMessage);
        assertFalse(Files.exists(path));

        codec.save(path, device, features, VMFDocumentFormat.XML);
        assertNativeFormat(path, "xml");
        IOException loadError = assertThrows(IOException.class, () -> codec.load(path, Device.class));
        assertTrue(loadError.getMessage().toLowerCase(Locale.ROOT).contains("explicit"), loadError::getMessage);
        assertEquals(device.getLabel(), codec.load(path, Device.class, VMFDocumentFormat.XML).owner().getLabel());
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyFormatOverloadsRemainCompatible(@TempDir Path directory) throws IOException {
        ModelRegistry registry = modelRegistry();
        Device device = Device.newBuilder().withLabel("Positioning stage").build();
        FeatureAttachments features = new FeatureAttachments(registry);
        VMFFeatureCodec codec = new VMFFeatureCodec(registry);
        Path path = directory.resolve("device.snapshot");

        codec.save(path, device, features, VMFFeatureCodec.Format.YAML);
        assertNativeFormat(path, "yaml");
        assertEquals(device.getLabel(), codec.load(path, Device.class, VMFDocumentFormat.YAML).owner().getLabel());

        codec.save(path, device, features, VMFDocumentFormat.YAML);
        assertEquals(device.getLabel(), codec.load(path, Device.class, VMFFeatureCodec.Format.YAML).owner().getLabel());
    }

    private static void assertNativeFormat(Path file, String extension) throws IOException {
        String document = Files.readString(file);
        switch (extension) {
            case "json":
                assertTrue(document.stripLeading().startsWith("{") && document.contains("\"owner\""), document);
                break;
            case "yaml":
            case "yml":
                assertTrue(document.lines().anyMatch(line -> line.startsWith("owner:")), document);
                break;
            case "xml":
                assertTrue(document.contains("<vmf-features") && document.contains("version=\"1\"")
                        && document.contains("<owner") && document.contains("<features"), document);
                assertTrue(document.contains("type=\"" + Device.class.getName() + "\"")
                        && document.contains("<property") && document.contains("name=\"label\"")
                        && document.contains("kind=\"string\"") && document.contains("Positioning stage"), document);
                break;
            default:
                throw new AssertionError("Unknown file extension: " + extension);
        }
    }

    private static ModelRegistry modelRegistry() {
        return new ModelRegistry(List.of(
                Device.type().reflect().allTypes(),
                CalibrationPlugin.types(),
                TelemetryPlugin.types()));
    }
}
