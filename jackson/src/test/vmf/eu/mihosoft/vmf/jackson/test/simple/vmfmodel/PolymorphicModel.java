package eu.mihosoft.vmf.jackson.test.simple.vmfmodel;

import eu.mihosoft.vmf.core.*;

interface MyModel {
    @Contains(opposite = "model")
    Person[] getPersons();
}

interface Person {
    String getName();

    @DefaultValue("30")
    int getAge();

    @Annotation(key = "vmf:jackson:description", value = "Residential address of the person.")
    Address getAddress();

    @Container(opposite = "persons")
    MyModel getModel();
}

interface Employee extends Person {
    String getEmployeeId();
}

@Immutable
interface Address {
    String getStreet();
    String getCity();
    @DefaultValue("\"10000\"")
    String getZip();
}
