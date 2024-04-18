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
package eu.mihosoft.vmftest.complex.supplier.vmfmodel;

import eu.mihosoft.vmf.core.*;

import java.util.Date;

@Doc("Supplier has customers and processes orders.")
interface Supplier {

    String getName();

    @Contains(opposite = "supplier")
    Customer[] getCustomers();

    @Contains(opposite = "supplier")
    PurchaseOrder[] getOrders();

}

@Doc("Customer of a supplier. It has a unique id.")
interface Customer {

    @Container(opposite = "customers")
    Supplier getSupplier();

    Integer getCustomerID();

    @Refers(opposite = "customer")
    PurchaseOrder[] getOrders();

}

@Doc("A purchase order.")
interface PurchaseOrder {

    String getComment();
    Date getDate();
    String getStatus();

    @Refers(opposite = "orders")
    Customer getCustomer();

    PurchaseOrder getPreviousOrder();

    @Contains(opposite = "purchaseOrder")
    Item[] getItems();

    @Contains()
    Address getBillTo();

    @Contains()
    Address getShipTo();

    @Container(opposite = "orders")
    Supplier getSupplier();
}

@Doc("Item provided by a supplier.")
interface Item {
    String getProductName();
    Integer getQuantity();
    Double getUSPrice();
    String getComment();
    Date getShipDate();

    String getPartNumber();

    @Container(opposite = "items")
    PurchaseOrder getPurchaseOrder();
}

@Doc("An address used for shippment and billing.")
@InterfaceOnly
interface Address {
    String getName();
}

@Doc("US address")
interface USAddress extends Address{

    Integer getZip();

    String getCity();

    String getStreet();

    String getState();
}


@Doc("Global address.")
interface GlobalAddress extends Address {
    String getCountry();

    Integer getZip();

    String getCity();

    String getStreet();
}
