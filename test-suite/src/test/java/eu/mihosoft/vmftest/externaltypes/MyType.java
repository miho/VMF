package eu.mihosoft.vmftest.externaltypes;

public interface MyType {
    default void seemsToWork() {
        System.out.println("Works!");
    }
}
