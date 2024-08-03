package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.test.Address;
import eu.mihosoft.vmf.jackson.test.Employee;
import eu.mihosoft.vmf.jackson.test.MyModel;
import eu.mihosoft.vmf.jackson.test.Person;

public class Main2 {


    public static void main(String[] args) {
//        System.out.println("TEST");
//
        // create a new instance of MyModel
        var model = MyModel.newBuilder().withPersons(
                Person.newBuilder().withName("John Doe").withAge(30)
                        .withAddress(Address.newBuilder().withCity("New York").withStreet("5th Ave").withZip("10001")).build(),
                Person.newBuilder().withName("Jane Doe").withAge(25)
                        .withAddress(Address.newBuilder().withCity("New York").withStreet("5th Ave").withZip("10001")).build(),
                Person.newBuilder().withName("Max Mustermann").withAge(40)
                        .withAddress(Address.newBuilder().withCity("Munich").withStreet("Marienplatz").withZip("80331")).build()
                ,Employee.newBuilder().withName("Maja Mustermann").withAge(39).withEmployeeId("1234")
                        .withAddress(Address.newBuilder().withCity("Munich").withStreet("Marienplatz").withZip("80331")).build()
        ).build();

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

//        try {
//            json = writer.writeValueAsString(model);
//            System.out.println(json);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        // deserialize the model
        var model2 = MyModel.newBuilder().build();

        try {
            model2 = mapper.readValue(json, MyModel.class);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // output second model
        try {
            json = writer.writeValueAsString(model2);
            System.out.println(json);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // output the model as xml
        var xmlMapper = new XmlMapper();
        xmlMapper.registerModule(new GenericBuilderModule()
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
        );

        try {
            String xml2 = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2);

            // read the model back and compare to model2
            var model3 = xmlMapper.readValue(xml2, MyModel.class);

            System.out.println(xml2);

            // output the model as xml
            String xml3 = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model3);
            System.out.println(xml3);

            System.out.println("Models are equal: " + model2.vmf().content().equals(model3));

        } catch (Exception e) {
            e.printStackTrace();
        }

        // output the model as yaml
        var yamlMapper = new YAMLMapper();
        yamlMapper.registerModule(new GenericBuilderModule()
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
        );

        try {
            String yaml = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model2);

            // read the model back and compare to model2
            var model3 = yamlMapper.readValue(yaml, MyModel.class);
            System.out.println(yaml);

            String yaml2 = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model3);
            System.out.println(yaml2);

            System.out.println("Models are equal: " + model2.vmf().content().equals(model3));

        } catch (Exception e) {
            e.printStackTrace();
        }




        // compare the models
        boolean equals = model.vmf().content().equals(model2);

        System.out.println("Models are equal: " + equals);


    }
}
