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
package eu.mihosoft.vmftest.cross_ref;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.concurrent.atomic.AtomicInteger;

import eu.mihosoft.vmf.runtime.core.VObject;

public class CrossRefTest {

    static AtomicInteger countChangeEvents(VObject o) {
        // count total change events
        AtomicInteger numChanges= new AtomicInteger(0);
        o.vmf().changes().addListener(change -> {
            System.out.println("change: ");
            System.out.println(" -> obj:     " + o.getClass() + ", value: " + System.identityHashCode(o));
            System.out.println(" -> evt-src: " + change.object().getClass() + ", value: " + System.identityHashCode(change.object()));
            System.out.print(" -> prop="+change.propertyName() + ": ");
            
            if(change.propertyChange().isPresent()) {
                System.out.println(", oldVal= " + System.identityHashCode(change.propertyChange().get().oldValue())
                                 + ", newVal= " + System.identityHashCode(change.propertyChange().get().newValue()));
            } else {
                System.out.println("<not present>");
            }

            numChanges.getAndIncrement();
        });

        return numChanges;
    }

    @Test
    public void singleRefTest() {
        {

            System.out.println("--- TEST singleRefTest");
            EntityOneA entityOneA = EntityOneA.newInstance();
            EntityTwoA entityTwoA = EntityTwoA.newInstance();

            final AtomicInteger numEvtOneA = countChangeEvents(entityOneA);
            final AtomicInteger numEvtTwoA = countChangeEvents(entityTwoA);

            entityOneA.vmf().changes().start();
            entityTwoA.vmf().changes().start();

            entityOneA.setRef(entityTwoA);

            assertThat("opposite ref must be set to ref", entityTwoA.getRef(), equalTo(entityOneA));
            assertThat("there's exactly one property 'ref' change", entityOneA.vmf().changes().all().size(), equalTo(1));
            assertThat("there's exactly one property 'ref' change-events", numEvtOneA.get(), equalTo(1));
            assertThat("there are two property 'ref' change-events", numEvtTwoA.get(), equalTo(1));
            assertThat("there's exactly one property 'ref' change. but it's only stored in the initiating object.", entityTwoA.vmf().changes().all().size(), equalTo(0));
        }

        {
            EntityOneA entityOneA = EntityOneA.newInstance();
            EntityTwoA entityTwoA = EntityTwoA.newInstance();

            final AtomicInteger numEvtOneA = countChangeEvents(entityOneA);
            final AtomicInteger numEvtTwoA = countChangeEvents(entityTwoA);

            entityOneA.vmf().changes().start();
            entityTwoA.vmf().changes().start();

            entityTwoA.setRef(entityOneA);

            assertThat("opposite ref must be set to ref", entityOneA.getRef(), equalTo(entityTwoA));
            assertThat("there's exactly one property 'ref' change", entityTwoA.vmf().changes().all().size(), equalTo(1));
            assertThat("there's exactly one property 'ref' change-events", numEvtTwoA.get(), equalTo(1));
            assertThat("there are two property 'ref' change-events", numEvtOneA.get(), equalTo(1));
            assertThat("there's exactly one property 'ref' change. but it's only stored in the initiating object.", entityOneA.vmf().changes().all().size(), equalTo(0));
        }
    }

    @Test
    public void singleMultipleRefTest() {

        System.out.println("--- TEST singleMultipleRefTest");

        {
            EntityOneB entityOneB = EntityOneB.newInstance();
            EntityTwoB entityTwoB = EntityTwoB.newInstance();

            final AtomicInteger numEvtOneB = countChangeEvents(entityOneB);
            final AtomicInteger numEvtTwoB = countChangeEvents(entityTwoB);


            entityOneB.vmf().changes().start();
            entityTwoB.vmf().changes().start();

            entityTwoB.getRefs().add(entityOneB);

            assertThat("opposite ref must be set to ref", entityOneB.getRef(), equalTo(entityTwoB));
            assertThat("there's exactly one property 'ref' change-events", numEvtOneB.get(), equalTo(1));
            assertThat("there's exactly one property 'ref' change-events", numEvtTwoB.get(), equalTo(1));
            assertThat("there's exactly one property 'ref' change.", entityTwoB.vmf().changes().all().size(), equalTo(1));
            assertThat("there's exactly one property 'ref' change. but it's only stored in the initiating object.", entityOneB.vmf().changes().all().size(), equalTo(0));

        }

        {

            EntityOneB entityOneB = EntityOneB.newInstance();
            EntityTwoB entityTwoB = EntityTwoB.newInstance();

            final AtomicInteger numEvtOneB = countChangeEvents(entityOneB);
            final AtomicInteger numEvtTwoB = countChangeEvents(entityTwoB);

            entityOneB.vmf().changes().start();
            entityTwoB.vmf().changes().start();

            entityOneB.setRef(entityTwoB);

            assertThat("opposite refs must contain ref", entityTwoB.getRefs(), contains(entityOneB));

            assertThat("there's exactly one property 'ref' change-events", numEvtOneB.get(), equalTo(1));
            assertThat("there's exactly one property 'ref' change-events", numEvtTwoB.get(), equalTo(1));

            assertThat("there's exactly one property 'ref' change. but it's only stored in the initiating object.", entityTwoB.vmf().changes().all().size(), equalTo(0));
            assertThat("there's exactly one property 'ref' change.", entityOneB.vmf().changes().all().size(), equalTo(1));
        }
    }
    @Test
    public void multipleMultipleRefTest() {

        System.out.println("--- TEST multipleMultipleRefTest");

        {
            EntityOneC entityOneC = EntityOneC.newInstance();
            EntityTwoC entityTwoC = EntityTwoC.newInstance();

            final AtomicInteger numEvtOneC = countChangeEvents(entityOneC);
            final AtomicInteger numEvtTwoC = countChangeEvents(entityTwoC);

            entityOneC.vmf().changes().start();
            entityTwoC.vmf().changes().start();

            entityOneC.getRefs().add(entityTwoC);

            assertThat("opposite refs must contain ref", entityTwoC.getRefs(), contains(entityOneC));

            assertThat("there's exactly one property 'ref' change-events", numEvtOneC.get(), equalTo(1));
            assertThat("there's exactly one property 'ref' change-events", numEvtTwoC.get(), equalTo(1));

            assertThat("there's exactly one property 'ref' change. but it's only stored in the initiating object.", entityTwoC.vmf().changes().all().size(), equalTo(0));
            assertThat("there's exactly one property 'ref' change.", entityOneC.vmf().changes().all().size(), equalTo(1));
            

        }
        {
            EntityOneC entityOneC = EntityOneC.newInstance();
            EntityTwoC entityTwoC = EntityTwoC.newInstance();

            final AtomicInteger numEvtOneC = countChangeEvents(entityOneC);
            final AtomicInteger numEvtTwoC = countChangeEvents(entityTwoC);

            entityOneC.vmf().changes().start();
            entityTwoC.vmf().changes().start();

            entityTwoC.getRefs().add(entityOneC);
            assertThat("opposite refs must contain ref", entityOneC.getRefs(), contains(entityTwoC));

            assertThat("there's exactly one property 'ref' change-events", numEvtOneC.get(), equalTo(1));
            assertThat("there's exactly one property 'ref' change-events", numEvtTwoC.get(), equalTo(1));

            assertThat("there's exactly one property 'ref' change. but it's only stored in the initiating object.", entityOneC.vmf().changes().all().size(), equalTo(0));
            assertThat("there's exactly one property 'ref' change.", entityTwoC.vmf().changes().all().size(), equalTo(1));
        }
    }
}
