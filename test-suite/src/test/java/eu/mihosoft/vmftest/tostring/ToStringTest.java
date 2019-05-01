package eu.mihosoft.vmftest.tostring;

import org.junit.Test;

public class ToStringTest {

    @Test
    public void testToStringCircular() {
        Parent p = Parent.newInstance();
        p.setName("parent");

        Child c = Child.newInstance();
        c.setName("child");

        p.setChild(c);
        c.setParent(p);

        System.out.println("str: " + p.toString());

    }

    @Test
    public void testToStringCircularCollection() {
        Parent2 p = Parent2.newInstance();
        p.setName("parent");

        Child2 c = Child2.newInstance();
        c.setName("child");

        p.getChildren().add(c);
        c.setParent(p);

        System.out.println("str: " + p.toString());

    }

}