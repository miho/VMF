package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

interface EngineFleet {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) Engine[] getEngines();
    @Contains IntLookup getLookup();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.EngineBehavior")
interface Engine {
    @DefaultValue("7") int getPower();
    int transform(int delta);
    String label(String prefix);
}

interface InheritedEngine extends Engine {
    @DefaultValue("10") int getRate();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.FastBehavior")
interface FastEngine extends Engine {
    @DefaultValue("30") int getBonus();
    @Override int transform(int delta);
}

@InterfaceOnly
interface Lookup<T extends Number> {
    T getValue();
    int metric(int delta);
}

interface IntLookup extends Lookup<Integer> {
    @Override
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.LookupBehavior")
    int metric(int delta);
}
