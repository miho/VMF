/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
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