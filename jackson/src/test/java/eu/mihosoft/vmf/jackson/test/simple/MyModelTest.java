package eu.mihosoft.vmf.jackson.test.simple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import org.junit.jupiter.api.Assertions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyModelTest {
    @org.junit.jupiter.api.Test
    void testModel() {
        ObjectMapper mapper = new ObjectMapper();

        mapper.registerModule(new VMFJacksonModule()
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
                .withTypeAlias("my-model", MyModel.class.getName())
        );

        var writer = mapper.writerWithDefaultPrettyPrinter();

        MyModel model = MyModel.newBuilder()
                .withPersons(Person.newBuilder()
                        .withName("John Doe")
                        .withAge(30)
                        .withAddress(Address.newBuilder()
                                .withCity("New York")
                                .withStreet("5th Ave")
                                .withZip("10001")
                                .build())
                        .build(),
                        Person.newBuilder()
                                .withName("Jane Doe")
                                .withAge(25)
                                .withAddress(Address.newBuilder()
                                        .withCity("New York")
                                        .withStreet("5th Ave")
                                        .withZip("10001")
                                        .build())
                                .build(),
                        Person.newBuilder()
                                .withName("Max Mustermann")
                                .withAge(40)
                                .withAddress(Address.newBuilder()
                                        .withCity("Munich")
                                        .withStreet("Marienplatz")
                                        .withZip("80331")
                                        .build())
                                .build(),
                        Employee.newBuilder()
                                .withName("Maja Mustermann")
                                .withAge(39)
                                .withEmployeeId("1234")
                                .withAddress(Address.newBuilder()
                                        .withCity("Munich")
                                        .withStreet("Marienplatz")
                                        .withZip("80331")
                                        .build())
                                .build()
                )
                .build();

        // serialize the model
        String json = null;
        try {
            json = writer.writeValueAsString(model);
        } catch (Exception e) {
            Assertions.fail(e);
            e.printStackTrace();
        }

        // deserialize the model
        MyModel model2 = null;
        try {
            model2 = mapper.readValue(json, MyModel.class);
        } catch (Exception e) {
            Assertions.fail(e);
            e.printStackTrace();
        }

        // check if models are equal
        assertTrue(model.vmf().content().equals(model2) , "Models must be equal");

        // output second model as json and compare to original model json
        try {
            var json2 = writer.writeValueAsString(model2);
            // check if json is equal to the original json
            assertEquals(json, json2);
            System.out.println(json2);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // output the model as xml
        var xmlMapper = new XmlMapper();
        xmlMapper.registerModule(new VMFJacksonModule()
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
                .withTypeAlias("my-model", MyModel.class.getName())
        );

        try {
            String xml = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);

            // read the model back and compare to model2
            var xmlModel = xmlMapper.readValue(xml, MyModel.class);

            // output the model as xml
            System.out.println("Model Serialized from Orig:");
            System.out.println(xml);

            // output the model as xml
            String xml3 = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(xmlModel);
            System.out.println("Model Serialized from Deserialized:");
            System.out.println(xml3);

            System.out.println("Models are equal: " + model.vmf().content().equals(xmlModel));

            Assertions.assertTrue(model.vmf().content().equals(xmlModel), "Models must be equal");

        } catch (Exception e) {
            Assertions.fail(e);
            e.printStackTrace();
        }

        // YAML
        var yamlMapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        yamlMapper.registerModule(new VMFJacksonModule()
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
                .withTypeAlias("my-model", MyModel.class.getName())
        );

        try {
            String yaml = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);

            // read the model back and compare to model2
            var yamlModel = yamlMapper.readValue(yaml, MyModel.class);

            // output the model as yaml
            System.out.println("Model Serialized from Orig:");
            System.out.println(yaml);

            // output the model as yaml
            String yaml3 = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(yamlModel);
            System.out.println("Model Serialized from Deserialized:");
            System.out.println(yaml3);

            System.out.println("Models are equal: " + model.vmf().content().equals(yamlModel));

            Assertions.assertTrue(model.vmf().content().equals(yamlModel), "Models must be equal");

        } catch (Exception e) {
            Assertions.fail(e);
            e.printStackTrace();
        }

//        // TOML
//        var tomlMapper = new ObjectMapper(new TomlFactory());
//        tomlMapper.registerModule(new VMFJacksonModule()
//                .withTypeAlias("person", Person.class.getName())
//                .withTypeAlias("employee", Employee.class.getName())
//                .withTypeAlias("my-model", MyModel.class.getName())
//        );
//
//        try {
//            String toml = tomlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
//
//            // output the model as toml
//            System.out.println("Model Serialized from Orig:");
//            System.out.println(toml);
//
//            // read the model back and compare to model2
//            var tomlModel = tomlMapper.readValue(toml, MyModel.class);
//
//            // output the model as toml
//            String toml3 = tomlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tomlModel);
//            System.out.println("Model Serialized from Deserialized:");
//            System.out.println(toml3);
//
//            System.out.println("Models are equal: " + model.vmf().content().equals(tomlModel));
//
//            Assertions.assertTrue(model.vmf().content().equals(tomlModel), "Models must be equal");
//
//        } catch (Exception e) {
//            Assertions.fail(e);
//            e.printStackTrace();
//        }

    }

}