package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmftest.externaltypes.Model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class StockRawExternalTypesConformance {
    private static final List<?> RAW = Arrays.asList("001", 7, null, true,
            List.of("nested"), Map.of("enabled", false), "live");

    private StockRawExternalTypesConformance() { }

    public static void main(String[] arguments) throws IOException {
        boolean write = "write".equals(arguments[0]);
        verify(write, Path.of(arguments[1]), write ? "java-" : "csharp-");
    }

    static void verify(boolean write, Path exchange, String producer) throws IOException {
        var original = model();
        for (boolean references : new boolean[] {true, false}) {
            for (String extension : List.of("json", "yaml")) {
                var mapper = extension.equals("json") ? new ObjectMapper() : new YAMLMapper();
                mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withReferences(references));
                var source = exchange.resolve(producer + "stock-raw-lists-" + references + "." + extension);
                if (write) Files.writeString(source, mapper.writeValueAsString(original));
                var restored = mapper.readValue(source.toFile(), Model.class);
                check(restored.getName().equals("stock") && restored.getList().equals(RAW),
                        "The raw external scalar preserves nested JSON-compatible values");
                check(restored.getLists().size() == 2 && restored.getLists().get(0).equals(RAW)
                                && restored.getLists().get(1) == null && restored.getLists().get(0) != restored.getList(),
                        "The outer model list preserves its null slot and serializes the host payload by value");

                for (String invalid : List.of("{\"list\":{}}", "{\"lists\":{}}",
                        "{\"lists\":[{}]}", "{\"lists\":[7]}")) {
                    var document = mapper.writeValueAsString(new ObjectMapper().readTree(invalid));
                    try {
                        mapper.readValue(document, Model.class);
                        throw new AssertionError("Invalid external raw list payload was accepted: " + invalid);
                    } catch (IOException expected) {
                        check(hasArrayDiagnostic(expected), "Invalid raw list fails at an array boundary: " + invalid);
                    }
                }
            }
        }
    }

    private static Model model() {
        var original = Model.newBuilder().withName("stock").build();
        var raw = new ArrayList<Object>(RAW.subList(0, RAW.size() - 1));
        original.setList(raw);
        original.runAction(caller -> {
            check(caller == original, "The behavior callback receives the exact owner");
            caller.getLists().add(raw);
            caller.getLists().add(null);
        });
        var view = original.asReadOnly();
        check(view.getList() == raw && view.getLists().get(0) == raw, "The read-only view preserves host aliases");
        raw.add("live");
        check(view.getList().equals(RAW), "Host mutations remain visible through the read-only view");
        try {
            view.getLists().clear();
            throw new AssertionError("The modeled collection remains read-only");
        } catch (UnsupportedOperationException expected) {
            check(view.getLists().size() == 2, "The rejected write preserves the outer list");
        }

        var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        original.setBImage(image);
        original.setImage(image);
        original.getBImages().add(image);
        original.getImages().add(image);
        check(view.getBImage() == image && view.getImage() == image
                        && view.getBImages().get(0) == image && view.getImages().get(0) == image,
                "Both original AWT model properties keep the exact native host value");
        original.setBImage(null);
        original.setImage(null);
        original.getBImages().clear();
        original.getImages().clear();
        return original;
    }

    private static boolean hasArrayDiagnostic(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("array")) return true;
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
