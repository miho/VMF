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
package eu.mihosoft.vmftest.complex.horses;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

public class HorsesTest {
    @Test public void horseTest() {

        Horse horse1 = Horse.newBuilder().withName("Larissa").build();
        Horse horse2 = Horse.newBuilder().withName("Dynastie").build();
        Horse horse3 = Horse.newBuilder().withName("Mike").build();
        
        Owner owner1 = Owner.newBuilder().withName("Horst Müller").withHorses(horse1).build();
        Owner owner2 = Owner.newBuilder().withName("Berta Schmidt").withHorses(horse2,horse3).build();

        HorseBarn barn1 = HorseBarn.newBuilder().withHorses(horse1,horse2).build();
        HorseBarn barn2 = HorseBarn.newBuilder().withHorses(horse3).build();
        
        Tournament tournament1 = Tournament.newBuilder().withName("Spring Tournament").withHorses(horse1, horse2, horse3).build();

        assertThat("Horses 1 and 2 must be contained by barn 1", barn1.getHorses(), contains(horse1, horse2));
        assertThat("Horse 3 must be contained by barn 2", barn2.getHorses(), contains(horse3));

        assertThat("Horse 1 must be owned by owner 1", horse1.getOwner(), equalTo(owner1));
        assertThat("Horse 2 must be owned by owner 2", horse2.getOwner(), equalTo(owner2));
        assertThat("Horse 3 must be owned by owner 2", horse3.getOwner(), equalTo(owner2));
        
        assertThat("Tournament 1 contains all horses", tournament1.getHorses(), contains(horse1, horse2, horse3));

        // move horse 1 from barn 1 to barn 2
        barn2.getHorses().add(horse1);
        assertThat("Horse 1 must be removed from barn 1", barn1.getHorses(), not(hasItem(horse1)));
        assertThat("Horse 1 must be contained by barn 2", barn2.getHorses(), hasItem(horse1));

        // owner 2 sells horse 3 to owner 1
        horse3.setOwner(owner1);
        System.out.println("horse3: " + horse3);
        System.out.println("horses of owner1:");

        owner1.getHorses().forEach(h-> System.out.println(" -> h: " + h));

        assertThat("Horse 3 must be owned by owner 1", owner1.getHorses(), hasItem(horse3));
        assertThat("Horse 3 must be removed from owner 2", owner2.getHorses(), not(hasItem(horse3)));

        // now we attend a second tournament. but since tournaments are only references we can be
        // referenced by multiple tournament objects
        Tournament tournament2 = Tournament.newBuilder().withName("Summer Tournament").withHorses(horse1, horse2, horse3).build();
        assertThat("Tournament 1 contains all horses", tournament1.getHorses(), hasItems(horse1, horse2, horse3));
        assertThat("Tournament 2 contains all horses", tournament2.getHorses(), hasItems(horse1, horse2, horse3));

        assertThat("Horse 1 attends two tournaments", horse1.getTournaments(), contains(tournament1, tournament2));
    }

    @Test public void crossRefTestForLists() {

        Owner owner = Owner.newBuilder().withName("Larry Smith").build();
        Horse horse1 = Horse.newBuilder().withName("Lady").build();
        
        // adding a horse to the same list multiple times should still result
        // in only one reference to this horse being contained
        owner.getHorses().add(horse1);
        owner.getHorses().add(horse1);
        owner.getHorses().add(horse1);

        assertThat("The horses list should only contain one reference to a horse", owner.getHorses().size(), equalTo(1));
        assertThat("Only one reference to horse 1 should be contained in the horses list", owner.getHorses(), contains(horse1));

    }
}