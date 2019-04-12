package eu.mihosoft.vmftest.complex.horses.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Doc;
import eu.mihosoft.vmf.core.Refers;

@Doc("A barn for horses.")
interface HorseBarn {

    @Doc("The horses contained in this barn.")
    @Contains()
    Horse[] getHorses();

}

@Doc("Owner of a horse or multiple horses.")
interface Owner {

    @Doc("Name of the owner.")
    String getName();

    @Doc("Horses owned by this owner.")
    @Contains(opposite = "owner")
    Horse[] getHorses();

}

@Doc("A horse.")
interface Horse {
    String getName();

    @Doc("Owner of this horse.")
    @Container(opposite = "horses")
    Owner getOwner();

    @Doc("Turnaments this horse attends.")
    @Refers(opposite = "horses")
    Turnament[] getTurnaments();
}

@Doc("Turnament")
interface Turnament {

    @Doc("Name of the turnament.")
    String getName();
    
    @Doc("Horses that attend this turnament.")
    @Refers(opposite="turnaments")
    Horse[] getHorses();
}
