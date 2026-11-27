package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.genericscalars.ForwardedScalarHookChannel;
import eu.mihosoft.vmf.conformance.genericscalars.HookedScalarReading;
import eu.mihosoft.vmf.conformance.genericscalars.ScalarHookChannel;
import eu.mihosoft.vmf.conformance.genericscalars.ScalarHookReadings;
import eu.mihosoft.vmf.conformance.genericscalars.TextScalarReading;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

final class ScalarHookConformance {
    private ScalarHookConformance() { }

    static void verify(boolean write, Path directory) throws IOException {
        for (var format : VMFDocumentFormat.values()) {
            for (boolean references : new boolean[]{false, true}) {
                var mapper = SecondaryScalarConformance.mapper(format, references);
                String name = (write ? "java" : "csharp") + "-generic-hook-" + references + "."
                        + format.name().toLowerCase(Locale.ROOT);
                var file = directory.resolve(name).toFile();
                if (write) {
                    var number = HookedScalarReading.newBuilder().withSample(7).build();
                    var text = TextScalarReading.newBuilder().withSample("label").build();
                    var readings = ScalarHookReadings.newBuilder().withNumber(number).withText(text).build();
                    verifyReadings(readings);
                    mapper.writeValue(file, readings);
                }
                verifyReadings(mapper.readValue(file, ScalarHookReadings.class));
            }
        }
    }

    private static void verifyReadings(ScalarHookReadings readings) {
        var number = readings.getNumber();
        ScalarHookChannel<Integer> boxed = number;
        ForwardedScalarHookChannel<String> forwarded = readings.getText();
        if (number.hookRuns() != 1 || boxed.getSample() != 7 || boxed.asReadOnly().getSample() != 7
                || !"label".equals(forwarded.asReadOnly().getSample())) {
            throw new AssertionError("Hook and closed scalar specializations retain the expected behavior");
        }
        var view = boxed.asReadOnly();
        boxed.setSample(null);
        if (view.getSample() != null || number.hookRuns() != 1) {
            throw new AssertionError("Nullable boxed values remain live through the projected channel");
        }
        boxed.setSample(7);
    }
}
