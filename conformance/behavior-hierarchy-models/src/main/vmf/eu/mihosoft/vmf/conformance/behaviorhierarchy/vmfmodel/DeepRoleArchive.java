package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.DeepRoleOracle.RootBinding")
interface RootService {
    @DefaultValue("11") int getRootValue();
    int choose(int value);
    int rootCalls();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.DeepRoleOracle.ConfigureBinding")
interface ConfiguredService extends RootService {
    @DefaultValue("22") int getSetting();
    int tune(int value);
    int configuredCalls();
}

interface LabeledService extends ConfiguredService {
    @DefaultValue("33") int getLabel();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.DeepRoleOracle.SpecialBinding")
interface SpecializedService extends LabeledService {
    @DefaultValue("44") int getSpecialty();
    @Override int choose(int value);
    int specializedCalls();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.DeepRoleOracle.PeerBinding")
interface PeerService {
    @DefaultValue("55") int getPeerValue();
    int choose(int value);
    int peerCalls();
}

interface SpecializedWins extends SpecializedService, PeerService { }
interface PeerWins extends PeerService, SpecializedService { }
interface LabeledWins extends LabeledService, PeerService { }
interface PeerWithLabel extends PeerService, LabeledService { }

interface DeepRoleArchive {
    @Contains RootService[] getItems();
    PeerService getSelected();
}
