package eu.mihosoft.vmf.jackson.test.executabledeserialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class ExecutableSnapshotDefaultsTest {
    private static final List<String> SINGLE_BUILD =
            List.of("numbers:1", "settings:1", "hook", "numbers:2", "settings:2");

    @Test
    public void coldAndWarmTypedValueReadsUseOneModelBuild() throws Exception {
        var mapper = mapper(false);
        for (int attempt = 0; attempt < 2; attempt++) {
            DefaultTrace.reset();
            var value = mapper.readValue("{}", HookedValues.class);
            assertEquals(SINGLE_BUILD, DefaultTrace.events);
            assertEquals(List.of(1), value.getNumbers());
            assertEquals(Map.of("saved-1", 1), value.getSettings());
        }

        DefaultTrace.reset();
        var explicit = mapper.readValue("{\"counts\":[27],\"settings\":{\"wire\":31}}", HookedValues.class);
        assertEquals(SINGLE_BUILD, DefaultTrace.events);
        assertEquals(List.of(27), explicit.getNumbers());
        assertEquals(Map.of("wire", 31), explicit.getSettings());
    }

    @Test
    public void coldAndWarmReferenceReadsUseOneModelBuildAndRetainWireAliases() throws Exception {
        var mapper = mapper(true);
        String document = "{\"picked\":\"child-1\",\"child\":{\"@vmf-id\":\"child-1\",\"label\":\"child\"}}";
        for (int attempt = 0; attempt < 2; attempt++) {
            DefaultTrace.reset();
            var value = mapper.readValue(document, HookedReferences.class);
            assertEquals(SINGLE_BUILD, DefaultTrace.events);
            assertEquals(List.of(1), value.getNumbers());
            assertEquals(Map.of("saved-1", 1), value.getSettings());
            assertSame(value.getChild(), value.getSelection());
            assertEquals("child", value.getSelection().getLabel());
        }
    }

    private static ObjectMapper mapper(boolean references) {
        return new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references));
    }
}
