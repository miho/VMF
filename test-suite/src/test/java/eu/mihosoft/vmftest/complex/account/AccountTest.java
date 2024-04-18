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