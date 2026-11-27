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
package eu.mihosoft.vmftest.complex.account;

import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class AbstractCustomerBuilderTest {
    @Test
    public void stockBuilderTransfersAbstractCustomersWithWritableModelInverse() {
        verifyTransfer(false);
        verifyTransfer(true);
    }

    private static void verifyTransfer(boolean append) {
        Customer one = PrivateCustomer.newBuilder().withFirstName("one").build();
        Customer two = BusinessCustomer.newBuilder().withCompanyName("two").build();
        Customer previous = PrivateCustomer.newBuilder().withFirstName("previous").build();
        var bank = Account.newBuilder().withName("bank").withAuthorizedSignatories(one, two).build();
        var former = Account.newBuilder().withName("former").build();
        var source = AccountModel.newBuilder().withAccounts(bank).withCustomers(one, null, two, one).build();
        var target = AccountModel.newBuilder().withAccounts(former).withCustomers(previous).build();
        var view = target.asReadOnly();
        var events = new ArrayList<String>();
        observe(source, "source", events);
        observe(target, "target", events);
        observe(one, "one", events);
        observe(two, "two", events);
        observe(previous, "previous", events);
        observe(bank, "bank", events);
        observe(former, "former", events);
        assertCustomers(source, null, two, one);

        var staged = AccountModel.newBuilder().appendCollections(append).applyFrom(source);
        assertTrue(events.isEmpty());
        assertSame(source, bank.getModel());
        assertSame(source, one.getModel());
        assertSame(source, two.getModel());
        staged.applyTo(target);
        assertCustomers(source, (Customer) null);
        assertTrue(source.getAccounts().isEmpty());
        assertCustomers(target, append ? new Customer[]{previous, null, two, one} : new Customer[]{null, two, one});
        assertAccounts(target, append ? new Account[]{former, bank} : new Account[]{bank});
        assertSame(append ? target : null, former.getModel());
        assertSame(append ? target : null, previous.getModel());
        assertAliases(target, bank, one, two);
        assertSame(one.asReadOnly(), view.getCustomers().get(append ? 3 : 2));
        assertSame(target.asReadOnly(), one.asReadOnly().getModel());
        assertEquals(append
                ? List.of("source.accounts", "bank.model", "bank.model", "target.accounts",
                    "source.customers", "two.model", "two.model", "source.customers", "one.model", "one.model", "target.customers")
                : List.of("former.model", "target.accounts", "source.accounts", "bank.model", "bank.model", "target.accounts",
                    "previous.model", "target.customers", "source.customers", "two.model", "two.model",
                    "source.customers", "one.model", "one.model", "target.customers"), events);

        events.clear();
        AccountModel.newBuilder().appendCollections(append).applyFrom(target).applyTo(target);
        assertCustomers(target, append ? new Customer[]{null, previous, null, two, one} : new Customer[]{null, two, one});
        assertAccounts(target, append ? new Account[]{former, bank} : new Account[]{bank});
        assertAliases(target, bank, one, two);
        assertEquals(append ? List.of("target.customers")
                : List.of("bank.model", "target.accounts", "bank.model", "target.accounts",
                    "two.model", "one.model", "target.customers", "two.model", "one.model", "target.customers"), events);

        events.clear();
        var built = staged.build();
        assertCustomers(built, null, two, one);
        assertAccounts(built, bank);
        assertAliases(built, bank, one, two);
        assertCustomers(target, append ? new Customer[]{null, previous, null} : new Customer[]{null});
        assertEquals(List.of("target.accounts", "bank.model", "bank.model", "target.customers", "two.model",
                "two.model", "target.customers", "one.model", "one.model"), events);
    }

    private static void assertCustomers(AccountModel model, Customer... expected) {
        var customers = model.getCustomers();
        assertEquals(expected.length, customers.size());
        for (int i = 0; i < expected.length; i++) assertSame(expected[i], customers.get(i));
    }

    private static void assertAccounts(AccountModel model, Account... expected) {
        var accounts = model.getAccounts();
        assertEquals(expected.length, accounts.size());
        for (int i = 0; i < expected.length; i++) assertSame(expected[i], accounts.get(i));
    }

    private static void assertAliases(AccountModel model, Account bank, Customer one, Customer two) {
        assertSame(model, bank.getModel());
        assertSame(model, one.getModel());
        assertSame(model, two.getModel());
        assertSame(one, bank.getAuthorizedSignatories().get(0));
        assertSame(two, bank.getAuthorizedSignatories().get(1));
        assertSame(bank, one.getAccounts().get(0));
        assertSame(bank, two.getAccounts().get(0));
    }

    private static void observe(VObject owner, String name, List<String> events) {
        owner.vmf().changes().addListener(change -> events.add(name + "." + change.propertyName()), false);
    }
}
