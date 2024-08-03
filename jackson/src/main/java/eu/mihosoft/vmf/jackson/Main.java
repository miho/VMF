package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.test.Address;
import eu.mihosoft.vmf.jackson.test.Employee;
import eu.mihosoft.vmf.jackson.test.MyModel;
import eu.mihosoft.vmf.jackson.test.Person;

public class Main {


    public static void main(String[] args) {

        // serialize the model
        ObjectMapper mapper = new ObjectMapper();

        mapper.registerModule(new GenericBuilderModule()
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
        );

        var writer = mapper.writerWithDefaultPrettyPrinter();

        String json = """
                {
                  "persons" : [ {
                    "address" : {
                      "zip" : "10001",
                      "city" : "New York",
                      "street" : "5th Ave"
                    },
                    "age" : 30,
                    "name" : "John Doe"
                  }, {
                    "address" : {
                      "zip" : "10001",
                      "city" : "New York",
                      "street" : "5th Ave"
                    },
                    "age" : 25,
                    "name" : "Jane Doe"
                  }, {
                    "address" : {
                      "zip" : "80331",
                      "city" : "Munich",
                      "street" : "Marienplatz"
                    },
                    "age" : 40,
                    "name" : "Max Mustermann"
                  }, {
                    "@vmf-type" : "employee",
                    "address" : {
                      "zip" : "80331",
                      "city" : "Munich",
                      "street" : "Marienplatz"
                    },
                    "age" : 39,
                    "employeeId" : "1234",
                    "name" : "Maja Mustermann"
                  } ]
                }
                """;

        // deserialize the model
        var model = MyModel.newBuilder().build();

        try {
            model = mapper.readValue(json, MyModel.class);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // output second model
        try {
            json = writer.writeValueAsString(model);
            System.out.println(json);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // output the model as xml
        var xmlMapper = new XmlMapper();
        xmlMapper.registerModule(new GenericBuilderModule()
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
                .withTypeAlias("my-model", eu.mihosoft.vmf.jackson.test.MyModel.class.getName())
        );

        try {
            String xml2 = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);

            // read the model back and compare to model2
            var model2 = xmlMapper.readValue(xml2, MyModel.class);

            System.out.println("Model Serialized from Orig:");
            System.out.println(xml2);

            // output the model as xml
            String xml3 = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2);
            System.out.println("Model Serialized from Deserialized:");
            System.out.println(xml3);

            System.out.println("Models are equal: " + model.vmf().content().equals(model2));

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
