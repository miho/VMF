package eu.mihosoft.vmf.jackson.test.vmfmodel;

import eu.mihosoft.vmf.core.*;

interface MyModel {
    @Contains()
    Person[] getPersons();
}

interface Person {
    String getName();
    int getAge();
    Address getAddress();
}

interface Employee extends Person {
    String getEmployeeId();
}

interface Address {
    String getStreet();
    String getCity();
    String getZip();
}
