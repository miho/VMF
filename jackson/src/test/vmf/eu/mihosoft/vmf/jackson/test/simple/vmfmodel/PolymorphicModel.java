package eu.mihosoft.vmf.jackson.test.simple.vmfmodel;

import eu.mihosoft.vmf.core.*;

interface MyModel {
    @Contains(opposite = "model")
    Person[] getPersons();
}

interface Person {
    String getName();

    @DefaultValue("30")
    @Annotation(key = "vmf:jackson:schema:constraint", value = "minimum=0")
    @Annotation(key = "vmf:jackson:schema:constraint", value = "maximum=99")
    int getAge();

    @Annotation(key = "vmf:jackson:schema:description", value = "Residential address of the person.")
    Address getAddress();

    @Container(opposite = "persons")
    MyModel getModel();
}

interface Employee extends Person {
    @Annotation(key = "vmf:jackson:schema:description", value = "Employee ID.")
    @Annotation(key = "vmf:jackson:schema:required", value = "true")
    @Annotation(key = "vmf:jackson:schema:constraint", value = "1234")
    String getEmployeeId();
}

@Immutable
interface Address {
    String getStreet();
    String getCity();
    @DefaultValue("\"10000\"")
    String getZip();
}
