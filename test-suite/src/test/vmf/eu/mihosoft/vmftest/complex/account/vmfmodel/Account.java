package eu.mihosoft.vmftest.complex.account.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Refers;

interface AccountModel {

    @Contains(opposite="model")
    Customer[] getCustomers();
    @Contains(opposite="model")
    Account[] getAccounts();
}

interface Account {
    String getName();

    @Refers(opposite="accounts")
    Customer[] getAuthorizedSignatories();

    @Container(opposite="accounts")
    AccountModel getModel();
}

@InterfaceOnly
interface Customer {
    
    @Refers(opposite="authorizedSignatories")
    Account[] getAccounts();

    @Container(opposite="customers")
    AccountModel getModel();
}

interface PrivateCustomer extends Customer {
    String getFirstName();
    String getLastName();

    Address getResidentialAddress();
}

interface BusinessCustomer extends Customer {

    String getCompanyName();

    Address getCompanyAddress();
}

interface Address {
    String getStreet();
    String getCity();
    String getPostal();
}