package eu.mihosoft.vmf.jackson.test.simple;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.test.external_types01.ExternalTypeModel01;
import eu.mihosoft.vmf.jackson.test.renameprops01.RenamePropsModel;
import org.junit.jupiter.api.Assertions;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonSchemaModelTest {

    // TODO: testModel() is not a valid test method yet. We need to validate the schema against the expectations.

    @org.junit.jupiter.api.Test
    void testModel() {
        ObjectMapper mapper = new ObjectMapper();

        mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
                .withTypeAlias("my-model", MyModel.class.getName())
        );

        var writer = mapper.writerWithDefaultPrettyPrinter();

        MyModel model = MyModel.newBuilder()
                .withPersons(eu.mihosoft.vmf.jackson.test.simple.Person.newBuilder()
                                .withName("John Doe")
                                .withAge(30)
                                .withAddress(Address.newBuilder()
                                        .withCity("New York")
                                        .withStreet("5th Ave")
                                        .withZip("10001")
                                        .build())
                                .build(),
                        eu.mihosoft.vmf.jackson.test.simple.Person.newBuilder()
                                .withName("Jane Doe")
                                .withAge(25)
                                .withAddress(Address.newBuilder()
                                        .withCity("New York")
                                        .withStreet("5th Ave")
                                        .withZip("10001")
                                        .build())
                                .build(),
                        eu.mihosoft.vmf.jackson.test.simple.Person.newBuilder()
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
            System.out.println(json);
        } catch (Exception e) {
            Assertions.fail(e);
            e.printStackTrace();
        }

        // create a json schema from model class
        try {
            Map<String, Object> schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withTypeAlias("person", Person.class.getName())
                    .withTypeAlias("employee", Employee.class.getName())
                    .withTypeAlias("my-model", MyModel.class.getName())
                    .generateSchema(MyModel.class);
            ObjectMapper schemaMapper = new ObjectMapper();
            String jsonSchema = schemaMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
            System.out.println(jsonSchema);
        } catch (Exception e) {
            Assertions.fail(e);
            e.printStackTrace();
        }
    }

}