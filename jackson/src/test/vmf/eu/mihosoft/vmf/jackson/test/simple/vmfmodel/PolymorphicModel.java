package eu.mihosoft.vmf.jackson.test.simple.vmfmodel;

import eu.mihosoft.vmf.core.*;

interface MyModel {
    @Contains(opposite = "model")
    Person[] getPersons();
}

interface Person {
    String getName();
    int getAge();

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
    String getZip();
}
