package eu.mihosoft.vmftest.complex.account.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Refers;

interface Account {
    String getName();

    @Refers(opposite="accounts")
    Customer[] getAuthorizedSignatories();
}

@InterfaceOnly
interface Customer {
    
    @Refers(opposite="authorizedSignatories")
    Account[] getAccounts();
}

interface PrivateCustomer extends Customer {
    String getFirstName();
    String getLastName();

    Address getResidentialAddress();
}

interface BusinessCustomer extends Customer {

    String getCompanyName();

    Address getComapanyAddress();
}

interface Address {
    String getStreet();
    String getCity();
    String getPostal();
}