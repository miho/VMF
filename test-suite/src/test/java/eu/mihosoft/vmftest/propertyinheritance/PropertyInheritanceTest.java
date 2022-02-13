package eu.mihosoft.vmftest.propertyinheritance;

public class PropertyInheritanceTest {
    

    public void propertyInheritanceTest01() {


        PropSample01 sample01 = PropSample01.newInstance();

        GCode1 gCode1 = GCode1.newInstance();

        gCode1.setLocation(LocationXY.newInstance());


    }
    
}
