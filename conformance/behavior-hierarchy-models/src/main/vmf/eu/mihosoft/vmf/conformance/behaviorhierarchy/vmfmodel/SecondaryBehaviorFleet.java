package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Multiplicity;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.PowerBehavior")
interface PoweredCell {
    @DefaultValue("4") int getEnergy();
    int amplify(int delta);
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.MeterBehavior")
interface MeteredCell {
    @DefaultValue("5") int getWeight();
    int advance(int delta);
    int calls();
    boolean callerIs(MeteredCell value);
}

interface ComposedCell extends PoweredCell, MeteredCell {
    String getName();
}

interface SupplyCell {
    @DefaultValue("11") int getVoltage();
}

interface StandaloneMeter extends SupplyCell, MeteredCell { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SpecialMeterBehavior")
interface SpecialCell extends ComposedCell {
    @Override int advance(int delta);
}

interface SecondaryBehaviorFleet {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) PoweredCell[] getCells();
    @Contains StandaloneMeter getStandalone();
    MeteredCell getMirror();
}
