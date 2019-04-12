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
