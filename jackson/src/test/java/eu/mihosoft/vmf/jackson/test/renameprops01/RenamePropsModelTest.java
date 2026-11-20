package eu.mihosoft.vmf.jackson.test.renameprops01;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Annotation;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RenamePropsModelTest {

    @org.junit.jupiter.api.Test
    void cachedBuilderStillReadsCurrentRenameAnnotation() throws JsonProcessingException {
        var mapper = new ObjectMapper().registerModule(
                VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
        assertEquals("COM1", mapper.readValue("{\"comPort\":\"COM1\"}", RenamePropsModel.class).getCOMPort());

        var property = RenamePropsModel.newInstance().vmf().reflect().propertyByName("cOMPort").orElseThrow();
        var annotations = property.annotations();
        int position = 0;
        while (!"vmf:jackson:rename".equals(annotations.get(position).getKey())) position++;
        var previous = annotations.set(position, new Annotation() {
            @Override public String getKey() {
                return "vmf:jackson:rename";
            }

            @Override public String getValue() {
                return "serialPort";
            }
        });
        try {
            assertEquals("COM2", mapper.readValue("{\"serialPort\":\"COM2\"}", RenamePropsModel.class).getCOMPort());
        } finally {
            annotations.set(position, previous);
        }
        assertEquals("COM3", mapper.readValue("{\"comPort\":\"COM3\"}", RenamePropsModel.class).getCOMPort());
    }

    @org.junit.jupiter.api.Test
    void coldBuilderLookupIgnoresPublicPrototypeListEditsAcrossLocales() throws JsonProcessingException {
        var mapper = new ObjectMapper().registerModule(
                VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
        var properties = LocaleAliasProbe.type().reflect().properties();
        int position = 0;
        while (!"cOMPort".equals(properties.get(position).getName())) position++;
        var foreign = RenamePropsModel.newInstance().vmf().reflect().propertyByName("cOMPort").orElseThrow();
        var previousProperty = properties.set(position, foreign);
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            var cold = mapper.readValue("{\"deviceId\":\"id-1\",\"serialPort\":\"COM1\"}", LocaleAliasProbe.class);
            assertEquals("id-1", cold.getId());
            assertEquals("COM1", cold.getCOMPort());

            Locale.setDefault(Locale.ENGLISH);
            var warm = mapper.readValue("{\"deviceId\":\"id-2\",\"serialPort\":\"COM2\"}", LocaleAliasProbe.class);
            assertEquals("id-2", warm.getId());
            assertEquals("COM2", warm.getCOMPort());
        } finally {
            Locale.setDefault(previousLocale);
            properties.set(position, previousProperty);
        }
    }

    @org.junit.jupiter.api.Test
    void testModel() throws JsonProcessingException {

        RenamePropsModel model = RenamePropsModel.newBuilder()
                .withCOMPort("COM1")
                .build();

        var mapper = new ObjectMapper();
        mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("rename-props-model", RenamePropsModel.class.getName())
        );


        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        System.out.println(json);

        // the property must be renamed via @Annotation("vmf:jackson:rename")
        assertTrue(json.contains("\"comPort\""), "property must be serialized as 'comPort'");
        assertFalse(json.contains("cOMPort"), "the original property name must not leak into JSON");

        // deserialize
        RenamePropsModel model2 = mapper.readValue(json, RenamePropsModel.class);

        // serialize again
        String json2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2);
        System.out.println(json2);

        assertEquals(json, json2);

        // check if deserialization worked
        assertTrue(model.vmf().content().equals(model2));


    }
}
