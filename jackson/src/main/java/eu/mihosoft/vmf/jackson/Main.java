package eu.mihosoft.vmf.jackson;

import java.util.ArrayList;
import java.util.List;

public class Main {

    public static class MyClass {
        private String name;
        private int age;
        private List<String> tags;
        private MyClass child;

        private final List<MyClass> children = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public MyClass getChild() {
            return child;
        }

        public void setChild(MyClass child) {
            this.child = child;
        }

        // children list
        public List<MyClass> getChildren() {
            return children;
        }

        public void setChildren(List<MyClass> children) {
            this.children.clear();
            this.children.addAll(children);
        }




    }

//    public static void main(String[] args) {
//            // create a model tree
//            MyClass model = new MyClass();
//            model.name = "John Doe";
//            model.age = 30;
//            model.tags = Arrays.asList("tag1", "tag2");
//            model.child = new MyClass();
//            model.child.name = "Jane Doe";
//            model.child.age = 25;
//            model.child.tags = Arrays.asList("tag3", "tag4");
//
//            // add children
//            model.children.add(new MyClass());
//            model.children.get(0).name = "Max Mustermann";
//            model.children.get(0).age = 40;
//            model.children.get(0).tags = Arrays.asList("tag5", "tag6");
//            model.children.add(new MyClass());
//            model.children.get(1).name = "Maja Mustermann";
//            model.children.get(1).age = 39;
//            model.children.get(1).tags = Arrays.asList("tag7", "tag8");
//
//
//
//            // serialize to TOML
//            ObjectMapper mapper = new ObjectMapper(new TomlFactory());
//            mapper.registerModule(new VMFJacksonModule());
//
//            // write the model
//            try {
//                mapper.registerModule(new VMFJacksonModule());
//                var writer = mapper.writerWithDefaultPrettyPrinter();
//                writer.writeValue(System.out, model);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
}
