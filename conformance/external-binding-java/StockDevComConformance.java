package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.*;
import eu.mihosoft.vmftest.complex.devcom.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class StockDevComConformance {
    private StockDevComConformance() {}
    private static final class Binding implements JournalBinding {
        @Override public String modelId() { return "stock-devcom"; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject value) {
            if (value instanceof DevComCheckpoint) return "checkpoint";
            if (value instanceof PortConfig) return "config";
            if (value instanceof PortInfo) return "port";
            if (value instanceof StateChangedEvent) return "event";
            throw new IllegalArgumentException("unknown value");
        }

        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }

        @Override public VObject create(String type, String id) {
            if (type.endsWith(".PortConfig")) return PortConfig.newBuilder().build();
            if (type.endsWith(".PortInfo")) return PortInfo.newBuilder().build();
            if (type.endsWith(".StateChangedEvent")) return StateChangedEvent.newBuilder().build();
            throw new IllegalArgumentException("unknown immutable type");
        }

        @Override public JournalValueCodecs valueCodecs() {
            return JournalValueCodecs.standard()
                .withImmutable("devcom.parity/v1", ParityBits.class, ParityBits::name, ParityBits::valueOf)
                .withImmutable("devcom.stop/v1", StopBits.class, StopBits::name, StopBits::valueOf)
                .withImmutable("devcom.state/v1", Device.State.class, Device.State::name, Device.State::valueOf);
        }
    }

    static void verify(boolean writing, Path directory, String producer) throws IOException {
        var defaults = PortConfig.newBuilder().build();
        check(((WithName)defaults).getName().equals("COM0") && defaults.getParityBits() == ParityBits.NO_PARITY
            && defaults.getStopBits() == StopBits.ONE_STOP_BIT && defaults.getBaudRate() == 115200
            && defaults.getNumberOfDataBits() == 8 && defaults.getSafetyTimeout() == 200
            && defaults.getWriteTimeout() == 0 && !defaults.isRS485ModeEnabled(), "original configuration defaults");
        var config = config();
        var port = port();
        check(port.equals(PortInfo.newBuilder().withName("COM7").withDescription("different").build()),
            "PortInfo compares the port name");
        check(((WithExtendedName)port).getExtendedName().equals("Port COM7"), "PortInfo shared readonly trait");
        var binding = new Binding();
        var expected = JournalRecorder.recordFrame(DevComCheckpoint.newInstance(), binding, "initial", 0, root -> {
            root.setConfig(config);
            root.setPort(port);
            root.setEvent(event(null));
        });
        try {
            JournalRecorder.recordFrame(DevComCheckpoint.newInstance(), binding, "initial", 0,
                root -> root.setEvent(event(clear(new Exception("offline")))));
            throw new AssertionError("Non-null Throwable was unexpectedly journaled");
        } catch (IllegalStateException unsupported) {
            check(unsupported.getMessage().contains("Portable journal recording failed"), unsupported.getMessage());
        }

        for (var format : VMFDocumentFormat.values()) {
            String ext = format.name().toLowerCase(Locale.ROOT);
            var mapper = mapper(ext);
            roundtrip(directory, writing, producer, ext, "config", config, PortConfig.class, mapper);
            roundtrip(directory, writing, producer, ext, "port", port, PortInfo.class, mapper);
            roundtrip(directory, writing, producer, ext, "event", event(null), StateChangedEvent.class, mapper);

            for (var pair : faults().entrySet()) {
                var fault = event(pair.getValue());
                if (format == VMFDocumentFormat.XML && (pair.getKey().equals("stack") || pair.getKey().equals("suppressed"))) {
                    try {
                        mapper.readValue(mapper.writeValueAsString(fault), StateChangedEvent.class);
                        throw new AssertionError("Stock Java read a populated Exception XML array unexpectedly");
                    } catch (com.fasterxml.jackson.databind.JsonMappingException expectedFailure) {
                        continue;
                    }
                }
                var restored = roundtrip(directory, writing, producer, ext, pair.getKey(), fault, StateChangedEvent.class, mapper);
                check(restored.getException() != null && Objects.equals(restored.getException().getMessage(),
                    pair.getValue().getMessage() == null && ext.equals("xml") ? "" : pair.getValue().getMessage()), "Throwable message " + pair.getKey() + " " + ext);
                if (pair.getKey().equals("cause")) check("busy".equals(restored.getException().getCause().getMessage()), "cause message");
                if (pair.getKey().equals("stack")) check(restored.getException().getStackTrace().length == 2, "stack frames");
                if (pair.getKey().equals("suppressed")) check(restored.getException().getSuppressed().length == 1, "suppressed");
                if (pair.getKey().equals("simple") && ext.equals("xml"))
                    check(restored.getException().getCause() != null && "".equals(restored.getException().getCause().getMessage()), "legacy XML cause normalization");
            }
            Path baselineFile = directory.resolve(producer + "stock-devcom-checkpoint." + ext);
            Path journalFile = directory.resolve(producer + "stock-devcom-journal." + ext);
            if (writing) {
                Files.writeString(baselineFile, mapper.writeValueAsString(DevComCheckpoint.newInstance()));
                Files.writeString(journalFile, VMFJournalCodec.write(expected, format));
            }
            var baseline = mapper.readValue(baselineFile.toFile(), DevComCheckpoint.class);
            var journal = VMFJournalCodec.read(Files.readString(journalFile), format);
            check(journal.wireVersion() == 1 && VMFJournalCodec.write(journal, VMFDocumentFormat.JSON)
                    .equals(VMFJournalCodec.write(expected, VMFDocumentFormat.JSON)), "existing V1 journal records");
            var replay = JournalReplayer.replay(baseline, binding, "initial", journal);
            check(replay.getConfig().getStopBits() == StopBits.TWO_STOP_BITS && replay.getConfig().getParityBits() == ParityBits.ODD_PARITY
                && replay.getPort().getName().equals("COM7") && replay.getEvent().getOldState() == Device.State.CONNECTING
                && replay.getEvent().getNewState() == Device.State.ERROR && baseline.getConfig() == null, "immutable replay");
        }
        System.out.println("Java verified stock DevCom defaults, named enum V1 journals, and supported Exception snapshots.");
    }

    private static PortConfig config() {
        return PortConfig.newBuilder().withName("COM7").withParityBits(ParityBits.ODD_PARITY)
            .withStopBits(StopBits.TWO_STOP_BITS).withRS485ModeEnabled(true).build();
    }

    private static PortInfo port() {
        return PortInfo.newBuilder().withName("COM7").withExtendedName("Port COM7").withDescription("USB").withLocation("left").build();
    }

    private static StateChangedEvent event(Exception fault) {
        return StateChangedEvent.newBuilder().withTimestamp(42).withOldState(Device.State.CONNECTING)
            .withNewState(Device.State.ERROR).withException(fault).build();
    }

    private static Map<String, Exception> faults() {
        var faults = new LinkedHashMap<String, Exception>();
        faults.put("simple", clear(new Exception("offline")));
        var stack = clear(new Exception("serial timeout"));
        stack.setStackTrace(new StackTraceElement[] { new StackTraceElement("demo.Driver", "open", "Driver.java", 42),
            new StackTraceElement("demo.Test", "boot", null, -2) });
        faults.put("stack", stack);
        faults.put("cause", clear(new Exception("open failed", clear(new IllegalStateException("busy")))));
        var suppressed = clear(new Exception("open failed"));
        suppressed.addSuppressed(clear(new IllegalArgumentException("cleanup failed")));
        faults.put("suppressed", suppressed);
        faults.put("null-message", clear(new Exception()));
        return faults;
    }
    private static Exception clear(Exception value) { value.setStackTrace(new StackTraceElement[0]); return value; }

    private static <T> T roundtrip(Path dir, boolean writing, String producer, String format, String name, T value, Class<T> type,
            ObjectMapper mapper) throws IOException {
        var expected = mapper.writeValueAsString(value);
        Path path = dir.resolve(producer + "stock-devcom-" + name + "." + format);
        if (writing) Files.writeString(path, expected);
        String actual = Files.readString(path);
        check(mapper.readTree(expected).equals(mapper.readTree(actual)), name + " must preserve the Jackson document shape in " + format);
        return mapper.readValue(actual, type);
    }

    private static ObjectMapper mapper(String format) {
        ObjectMapper mapper;
        if (format.equals("xml")) mapper = new XmlMapper();
        else if (format.equals("yaml")) mapper = new YAMLMapper();
        else mapper = new ObjectMapper();
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(false));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
