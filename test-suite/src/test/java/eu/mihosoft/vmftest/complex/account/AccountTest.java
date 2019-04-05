package eu.mihosoft.vmftest.complex.account;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

public class AccountTest {
    @Test
    public void testCrossReference() {

        AccountModel model = AccountModel.newInstance();

        Account a1 = Account.newInstance();
        model.getAccounts().add(a1);
        Account a2 = Account.newInstance();
        model.getAccounts().add(a2);

        PrivateCustomer c1 = PrivateCustomer.newBuilder().
          withFirstName("John").withLastName("Potter").build();

        a1.getAuthorizedSignatories().add(c1);  
        model.getCustomers().add(c1);  
        a2.getAuthorizedSignatories().add(c1);  
        model.getCustomers().add(c1);  

        assertThat("account 1 must contain our customer", a1.getAuthorizedSignatories(), contains(c1));
        assertThat("account 1 has exactly one customer", a1.getAuthorizedSignatories().size(), equalTo(1));
        assertThat("account 2 must contain our customer", a2.getAuthorizedSignatories(), contains(c1));
        assertThat("account 2 has exactly one customer", a2.getAuthorizedSignatories().size(), equalTo(1));
        
        assertThat("our customer has both accounts", c1.getAccounts(), contains(a1,a2));
        assertThat("our customer has exactly two accounts", c1.getAccounts().size(), equalTo(2));

        a1.getAuthorizedSignatories().remove(c1);

        assertThat("after removing our customer from a1 our customer has exactly one account", c1.getAccounts().size(), equalTo(1));
        assertThat("our customer has only account a2", c1.getAccounts(), contains(a2));
    }
}