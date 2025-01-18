package eu.mihosoft.vmf.jackson.test.type_formats01;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.jackson.test.simple.Address;
import eu.mihosoft.vmf.jackson.test.simple.Employee;
import eu.mihosoft.vmf.jackson.test.simple.MyModel;
import eu.mihosoft.vmf.jackson.test.simple.Person;
import org.junit.jupiter.api.Assertions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatModelTest {
    @org.junit.jupiter.api.Test
    void testModel() {
        ObjectMapper mapper = new ObjectMapper();

        mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("pet", Pet.class.getName())
        );

        var writer = mapper.writerWithDefaultPrettyPrinter();

        // generate schema
        VMFJsonSchemaGenerator schemaGenerator =
                VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("pet", Pet.class.getName());

        var schema = schemaGenerator.generateSchemaAsString(FormatModel01.class);

        System.out.println(schema);

    }

}