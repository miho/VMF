package eu.mihosoft.vmf.conformance.immutables.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.List;
import java.util.Set;

@Immutable
@VMFEquals(VMFEquals.EqualsType.ALL)
interface ImmutablePortfolio {
    @DefaultValue("\"ready\"")
    @Annotation(key = "vmf:jackson:rename", value = "portfolio_status")
    String getStatus();

    @DefaultValue("-0.0d") Double getDefaultProbe();
    String[] getAliases();
    @Multiplicity(upper = 3) List<Integer> getCounts();
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) List<String> getSteps();
    Set<Double> getLevels();
    VMap<String, Long> getLimits();
    VMap<Double, Character> getMarkers();
}

@Immutable
@VMFEquals(VMFEquals.EqualsType.ALL)
interface ExtendedPortfolio extends ImmutablePortfolio {
    ImmutableBadge getCurrent();
    ImmutableBadge[] getTrail();
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) ImmutableBadge[] getPreferred();
    Set<ImmutableBadge> getPool();
}

@Immutable
interface IdentityPortfolio extends ImmutablePortfolio { }

@Immutable
@VMFEquals(VMFEquals.EqualsType.ALL)
interface ImmutableBadge {
    String getText();
}

interface PortfolioSelection {
    ImmutablePortfolio getPortfolio();
}
