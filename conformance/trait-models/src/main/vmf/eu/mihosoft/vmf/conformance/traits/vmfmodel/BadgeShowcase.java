package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

@InterfaceOnly
interface BadgeTraits {
    @GetterOnly
    @DefaultValue("\"draft\"")
    @Annotation(key = "vmf:jackson:rename", value = "display_name")
    String getName();

    @GetterOnly List<String> getTags();

    @GetterOnly
    @Multiplicity(upper = 3)
    List<Integer> getMeasures();

    @GetterOnly ColorToken getAccent();
    @GetterOnly Set<ColorToken> getSwatches();
    @GetterOnly VMap<String, Long> getScores();
}

@InterfaceOnly
interface SourceTraits {
    @GetterOnly @DefaultValue("\"warehouse\"") String getOrigin();
}

@Immutable
interface ColorToken {
    String getCode();
}

@Immutable
interface FixedBadgeBase {
    String getCategory();
}

@Immutable
@Annotation(key = "vmf:wire:type", value = "fixed-badge")
interface FixedBadge extends FixedBadgeBase, BadgeTraits, SourceTraits { }

@Annotation(key = "vmf:wire:type", value = "mutable-badge")
interface MutableBadge extends BadgeTraits, SourceTraits { }

interface BadgeShowcase {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    MutableBadge[] getOwned();

    BadgeTraits getSelected();
    BadgeTraits[] getChoices();
}
