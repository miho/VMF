package eu.mihosoft.vmf.jackson.test.playground;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.SchemaGeneratorTypeConfigPart;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.test.renameprops01.RenamePropsModel;
import eu.mihosoft.vmf.jackson.test.simple.Employee;
import eu.mihosoft.vmf.jackson.test.simple.MyModel;
import eu.mihosoft.vmf.jackson.test.simple.Person;
import eu.mihosoft.vmf.jackson.test.simple.MyModel;


public class Main {
    public static void main(String[] args) {

        RenamePropsModel model = RenamePropsModel.newBuilder()
                .withCOMPort("COM1")
                .build();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)

        );

        // Custom module to handle JavaBean-style getters and setters
        Module customModule = new Module() {
            @Override
            public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
                System.out.println("!!!applyToConfigBuilder");

                builder.forMethods()
                        .withIgnoreCheck(method -> {
                            String methodName = method.getName();
                            boolean isGetter = methodName.startsWith("get") && method.getArgumentCount() == 0;
                            boolean isSetter = methodName.startsWith("set") && method.getArgumentCount() == 1;
                            boolean shouldInclude = isGetter || isSetter;
                            System.out.println("Checking method: " + methodName + ", include: " + shouldInclude);
                            return !shouldInclude;
                        })
                        .withPropertyNameOverrideResolver(method -> {
                            String methodName = method.getName();
                            System.out.println("!!!methodName: " + methodName);
                            if (methodName.startsWith("get") && method.getArgumentCount() == 0) {
                                return decapitalize(methodName.substring(3));
                            } else if (methodName.startsWith("set") && method.getArgumentCount() == 1) {
                                return decapitalize(methodName.substring(3));
                            }
                            return null;
                        });

                builder.forFields().withIgnoreCheck(field -> true); // Ignore all fields, focus on methods

                builder.withObjectMapper(objectMapper);

//                builder.forTypesInGeneral().with

//                builder.with(new SchemaGeneratorTypeConfigPart() {
//                    @Override
//                    public void applyToConfigBuilder(SchemaGeneratorConfigPart<TypeScope> configPart) {
//                        configPart
//                                .withInstanceAttributeOverride(type -> type.getDeclaredMethods(), (context, method) -> {
//                                    if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
//                                        return new CustomPropertyNameProvider(method, decapitalize(method.getName().substring(3)));
//                                    } else if (method.getName().startsWith("set") && method.getParameterCount() == 1) {
//                                        return new CustomPropertyNameProvider(method, decapitalize(method.getName().substring(3)));
//                                    }
//                                    return null;
//                                });
//                    }
//                });
            }
        };

        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(objectMapper, SchemaVersion.DRAFT_2019_09, OptionPreset.PLAIN_JSON)
                .with(customModule);

        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator generator = new SchemaGenerator(config);

        // Generate schema for the Person interface
        String schema = generator.generateSchema(Person.class).toString();
        System.out.println(schema);
    }

    private static String decapitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }
}
