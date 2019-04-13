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
        
        Turnament turnament1 = Turnament.newBuilder().withName("Spring Turnament").withHorses(horse1, horse2, horse3).build();

        assertThat("Horses 1 and 2 must be contained by barn 1", barn1.getHorses(), contains(horse1, horse2));
        assertThat("Horse 3 must be contained by barn 2", barn2.getHorses(), contains(horse3));

        assertThat("Horse 1 must be owned by owner 1", horse1.getOwner(), equalTo(owner1));
        assertThat("Horse 2 must be owned by owner 2", horse2.getOwner(), equalTo(owner2));
        assertThat("Horse 3 must be owned by owner 2", horse3.getOwner(), equalTo(owner2));
        
        assertThat("Turnament 1 contains all horses", turnament1.getHorses(), contains(horse1, horse2, horse3));

        // move horse 1 from barn 1 to barn 2
        barn2.getHorses().add(horse1);
        assertThat("Horse 1 must be removed from barn 1", barn1.getHorses(), not(hasItem(horse1)));
        assertThat("Horse 1 must be contained by barn 2", barn2.getHorses(), hasItem(horse1));

        // owner 2 sells horse 3 to owner 1
        horse3.setOwner(owner1);
        assertThat("Horse 3 must be owned by owner 1", owner1.getHorses(), hasItem(horse3));
        assertThat("Horse 3 must be removed from owner 2", owner2.getHorses(), not(hasItem(horse3)));

        // now we attend a second turnament. but since turnaments are only references we can be
        // referenced by multiple turnament objects
        Turnament turnament2 = Turnament.newBuilder().withName("Summer Turnament").withHorses(horse1, horse2, horse3).build();
        assertThat("Turnament 1 contains all horses", turnament1.getHorses(), hasItems(horse1, horse2, horse3));
        assertThat("Turnament 2 contains all horses", turnament2.getHorses(), hasItems(horse1, horse2, horse3));

        assertThat("Horse 1 attends two turnaments", horse1.getTurnaments(), contains(turnament1, turnament2));
    }

    @Test public void containmentTestForLists() {

        Owner owner = Owner.newBuilder().withName("Larry Smith").build();
        Horse horse1 = Horse.newBuilder().withName("Lady").build();
        
        // adding a horse to the same list multiple times should still result
        // in only one reference to this horse being contained
        owner.getHorses().add(horse1);
        owner.getHorses().add(horse1);
        owner.getHorses().add(horse1);

        assertThat("Only one reference to horse 1 should be contained in the horses list", owner.getHorses(), contains(horse1));
        assertThat("The horses list should only contain one reference to a horse", owner.getHorses().size(), equalTo(1));

    }
}