package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.Set;

interface ReferenceDefaultDocument {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) SelectionTarget[] getTargets();
    @Contains MutablePicks getMutable();
    FrozenPicks getFrozen();
}

interface SelectionTarget {
    String getCode();
    SelectionTarget getLink();
}

interface MutablePicks {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ReferenceDefaultBindings.favorites()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Annotation(key = "vmf:jackson:rename", value = "picked")
    Set<SelectionTarget> getFavorites();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ReferenceDefaultBindings.featured()")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    SelectionTarget[] getFeatured();
}

@Immutable @VMFEquals(VMFEquals.EqualsType.ALL)
interface SelectionToken {
    @IgnoreEquals String getKey();
    String getLabel();
}

@Immutable
interface FrozenPicks {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ReferenceDefaultBindings.tokens()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<SelectionToken> getFavorites();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ReferenceDefaultBindings.tokens()")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    SelectionToken[] getFeatured();
}

interface LegacyReciprocalDocument {
    @Contains LegacyGroup getGroup();
    @Contains LegacyMember getMember();
}

interface LegacyGroup { @Refers(opposite = "groups") Set<LegacyMember> getMembers(); }

interface LegacyMember { @Refers(opposite = "members") Set<LegacyGroup> getGroups(); }
