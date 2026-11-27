package eu.mihosoft.vmf.conformance.opposites.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;

import java.util.Set;

interface ReciprocalGraph {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    Endpoint[] getNodes();
}

interface Endpoint {
    String getId();

    @Refers(opposite = "peer") Endpoint getPeer();

    @Refers(opposite = "reports") Endpoint getSupervisor();
    @Refers(opposite = "supervisor") Endpoint[] getReports();

    @Refers(opposite = "members") Endpoint[] getGroups();
    @Refers(opposite = "groups") Set<Endpoint> getMembers();

    @Refers(opposite = "panels")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    Endpoint[] getLinks();

    @Refers(opposite = "links") Set<Endpoint> getPanels();
}

interface PartnerGraph {
    @Contains Partner[] getPartners();
}

interface Partner {
    String getName();
    @Refers(opposite = "partner") Partner getPartner();
}

interface EventNamedFields {
    String getPropertyChanged();
    String getEvent();
}

interface OrderedPanelGallery {
    @Contains OrderedPanel getPanel();
    @Contains OrderedPanelLink[] getEntries();
}

interface OrderedPanel {
    String getId();

    @Refers(opposite = "panels")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    OrderedPanelLink[] getLinks();
}

interface OrderedPanelLink {
    String getId();
    @Refers(opposite = "links") Set<OrderedPanel> getPanels();
}
