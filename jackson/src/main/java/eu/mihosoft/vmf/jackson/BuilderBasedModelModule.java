package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.jackson.test.Address;
import eu.mihosoft.vmf.jackson.test.Employee;
import eu.mihosoft.vmf.jackson.test.MyModel;
import eu.mihosoft.vmf.jackson.test.Person;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BuilderBasedModelModule extends SimpleModule {

    public BuilderBasedModelModule() {
        setDeserializerModifier(new BuilderBasedDeserializerModifier());
    }

    private static class BuilderBasedDeserializerModifier extends BeanDeserializerModifier {
        @Override
        public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
//            if (MyModel.class.isAssignableFrom(beanDesc.getBeanClass())) {
//                return new BuilderBasedDeserializer<>(MyModel.class, deserializer);
//            } else if (Person.class.isAssignableFrom(beanDesc.getBeanClass())) {
//                return new BuilderBasedDeserializer<>(Person.class, deserializer);
//            } else if (Address.class.isAssignableFrom(beanDesc.getBeanClass())) {
//                return new BuilderBasedDeserializer<>(Address.class, deserializer);
//            }
//            return deserializer;

            var deserializerClass = beanDesc.getBeanClass();

            var builderBasedDeserializer = new BuilderBasedDeserializer<>(deserializerClass, deserializer);

            return builderBasedDeserializer;
        }
    }

    private static class BuilderBasedDeserializer<T> extends StdDeserializer<T> {

        private final JsonDeserializer<T> defaultDeserializer;

        protected BuilderBasedDeserializer(Class<?> vc, JsonDeserializer<T> defaultDeserializer) {
            super(vc);
            this.defaultDeserializer = defaultDeserializer;
        }

        @Override
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            ObjectNode node = p.getCodec().readTree(p);
            if (_valueClass == MyModel.class) {
                return (T) deserializeMyModel(node, ctxt);
            } else if (_valueClass == Person.class) {
                if (node.has("employeeId")) {
                    return (T) deserializeEmployee(node, ctxt);
                } else {
                    return (T) deserializePerson(node, ctxt);
                }
            } else if (_valueClass == Address.class) {
                return (T) deserializeAddress(node, ctxt);
            }
            return defaultDeserializer.deserialize(p, ctxt);
        }

        private MyModel deserializeMyModel(ObjectNode node, DeserializationContext ctxt) throws IOException {
            MyModel.Builder builder = MyModel.newBuilder();
            // Deserialize and set properties using builder
            List<Person> persons = new ArrayList<>();
            ArrayNode personsNode = (ArrayNode) node.get("persons");
            for (JsonNode personNode : personsNode) {
                if (personNode.has("employeeId")) {
                    persons.add(ctxt.readValue(personNode.traverse(ctxt.getParser().getCodec()), Employee.class));
                } else {
                    persons.add(ctxt.readValue(personNode.traverse(ctxt.getParser().getCodec()), Person.class));
                }
            }
            builder.withPersons(persons.toArray(new Person[0]));
            return builder.build();
        }

        private Person deserializePerson(ObjectNode node, DeserializationContext ctxt) throws IOException {
            Person.Builder builder = Person.newBuilder();
            builder.withName(node.get("name").asText());
            builder.withAge(node.get("age").asInt());
            builder.withAddress(ctxt.readValue(node.get("address").traverse(ctxt.getParser().getCodec()), Address.class));
            return builder.build();
        }

        private Employee deserializeEmployee(ObjectNode node, DeserializationContext ctxt) throws IOException {
            Employee.Builder builder = Employee.newBuilder();
            builder.withName(node.get("name").asText());
            builder.withAge(node.get("age").asInt());
            builder.withEmployeeId(node.get("employeeId").asText());
            builder.withAddress(ctxt.readValue(node.get("address").traverse(ctxt.getParser().getCodec()), Address.class));
            return builder.build();
        }

        private Address deserializeAddress(ObjectNode node, DeserializationContext ctxt) {
            Address.Builder builder = Address.newBuilder();
            builder.withCity(node.get("city").asText());
            builder.withStreet(node.get("street").asText());
            builder.withZip(node.get("zip").asText());
            return builder.build();
        }
    }
}