package eu.mihosoft.vmf.conformance.hierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface LeftRate {
    @Annotation(key = "vmf:jackson:rename", value = "chosen_rate")
    @DefaultValue("23") int getRate();
}

@InterfaceOnly
interface RightRate {
    @Annotation(key = "vmf:jackson:rename", value = "chosen_rate")
    @DefaultValue("42") int getRate();
}

interface LiteralRate extends LeftRate, RightRate { }
interface ReversedLiteralRate extends RightRate, LeftRate { }

@Immutable
interface LeftRank {
    @Annotation(key = "vmf:jackson:rename", value = "chosen_rank")
    @DefaultValue("5") int getRank();
}

@Immutable
interface RightRank {
    @Annotation(key = "vmf:jackson:rename", value = "chosen_rank")
    @DefaultValue("11") int getRank();
}

@Immutable
interface LiteralRank extends LeftRank, RightRank { }

@Immutable
interface ReversedLiteralRank extends RightRank, LeftRank { }

@InterfaceOnly
interface DefaultlessRate {
    @Annotation(key = "vmf:jackson:rename", value = "chosen_rate")
    int getRate();
}

interface ImplicitRate extends DefaultlessRate, LeftRate { }
interface InheritedLiteralRate extends LeftRate, DefaultlessRate { }

interface LocalLiteralRate extends LeftRate, RightRate {
    @Override
    @Annotation(key = "vmf:jackson:rename", value = "chosen_rate")
    @DefaultValue("71") int getRate();
}

@Immutable
interface DefaultlessRank {
    @Annotation(key = "vmf:jackson:rename", value = "chosen_rank")
    int getRank();
}

@Immutable
interface ImplicitRank extends DefaultlessRank, LeftRank { }

@Immutable
interface InheritedLiteralRank extends LeftRank, DefaultlessRank { }

@Immutable
interface LocalLiteralRank extends DefaultlessRank, LeftRank {
    @Override
    @Annotation(key = "vmf:jackson:rename", value = "chosen_rank")
    @DefaultValue("17") int getRank();
}
