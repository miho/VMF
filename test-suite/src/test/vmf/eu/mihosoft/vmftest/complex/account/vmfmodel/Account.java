package eu.mihosoft.vmftest.complex.account.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Refers;
import eu.mihosoft.vmf.core.Doc;

interface AccountModel {

    @Contains(opposite="model")
    Customer[] getCustomers();
    @Contains(opposite="model")
    Account[] getAccounts();
}

@Doc("A bank account has one or more authorized signatories.")
interface Account {
    String getName();

    @Refers(opposite="accounts")
    Customer[] getAuthorizedSignatories();

    @Container(opposite="accounts")
    AccountModel getModel();
}

@Doc("A customer can have one or more bank accounts.")
@InterfaceOnly
interface Customer {
    
    @Doc("Returns all bank accounts of this customer.")
    @Refers(opposite="authorizedSignatories")
    Account[] getAccounts();

    @Container(opposite="customers")
    AccountModel getModel();
}

@Doc("A private customer has a name and a residential address.")
interface PrivateCustomer extends Customer {
    String getFirstName();
    String getLastName();

    Address getResidentialAddress();
}

@Doc("A business customer is a company.")
interface BusinessCustomer extends Customer {

    String getCompanyName();

    Address getCompanyAddress();
}

@Doc("An address for customers.")
interface Address {
    String getStreet();
    String getCity();
    String getPostal();
}