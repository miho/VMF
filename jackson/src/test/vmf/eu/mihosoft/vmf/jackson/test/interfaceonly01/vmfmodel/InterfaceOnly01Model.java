package eu.mihosoft.vmf.jackson.test.interfaceonly01.vmfmodel;

import eu.mihosoft.vmf.core.*;

@InterfaceOnly
interface WithAddress {
    Address getAddress();
}

@InterfaceOnly
interface PersonBase {
    String getName();
    int getAge();
}

interface OrdinaryPerson extends PersonBase, WithAddress {

}

interface Employee extends PersonBase, WithAddress {
    String getEmployeeId();
}

@Immutable
interface Address {
    String getStreet();
    String getCity();
    String getZip();
}

interface MyModel {
    @Contains()
    PersonBase[] getPersons();
}
