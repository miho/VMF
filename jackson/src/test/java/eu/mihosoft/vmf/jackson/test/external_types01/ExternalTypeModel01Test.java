package eu.mihosoft.vmf.jackson.test.external_types01;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExternalTypeModel01Test {

    @org.junit.jupiter.api.Test
    void testModel() throws JsonProcessingException {

        ExternalTypeModel01 model = ExternalTypeModel01.newBuilder()
                .withName("Model 01")
                .withEnumValue(ExternalEnum.SECOND)
                .withTags(new String[]{"tag1", "tag2", "tag3"})
                .withValues(new Integer[]{1, 2, 3})
                .build();

        var mapper = new ObjectMapper();

        mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("external-type-model-01", ExternalTypeModel01.class.getName())
                .withTypeAlias("external-enum", ExternalEnum.class.getName())
        );

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        System.out.println(json);

        // deserialize
        ExternalTypeModel01 model2 = mapper.readValue(json, ExternalTypeModel01.class);

        // serialize again
        String json2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2);
        System.out.println(json2);

        // check if deserialization worked
        assertTrue(model.vmf().content().equals(model2));


        // XML

        var xmlMapper = new XmlMapper();
        xmlMapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("external-type-model-01", ExternalTypeModel01.class.getName())
                .withTypeAlias("external-enum", ExternalEnum.class.getName())
        );

        String xml = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        System.out.println(xml);

        ExternalTypeModel01 xmlModel = xmlMapper.readValue(xml, ExternalTypeModel01.class);

        String xml2 = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(xmlModel);
        System.out.println(xml2);

        assertTrue(model.vmf().content().equals(xmlModel));

        // YAML
        var yamlMapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        yamlMapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("external-type-model-01", ExternalTypeModel01.class.getName())
                .withTypeAlias("external-enum", ExternalEnum.class.getName())
        );

        String yaml = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        System.out.println(yaml);

        ExternalTypeModel01 yamlModel = yamlMapper.readValue(yaml, ExternalTypeModel01.class);

        String yaml2 = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(yamlModel);
        System.out.println(yaml2);

        assertTrue(model.vmf().content().equals(yamlModel));


    }

}
