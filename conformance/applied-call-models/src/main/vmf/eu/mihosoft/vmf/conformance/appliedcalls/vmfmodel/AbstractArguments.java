package eu.mihosoft.vmf.conformance.appliedcalls.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface PrimaryArgument {
    String getName();
}

interface PrimaryArgumentLeaf extends PrimaryArgument {
    String getData();
}

@InterfaceOnly
interface SecondaryArgument {
    String getLabel();
}

interface ArgumentHeader {
    String getTitle();
}

interface ComposedArgument extends ArgumentHeader, SecondaryArgument { }

interface ArgumentCallHost {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AbstractArgumentsConformance.Binding")
    PrimaryArgument rename(PrimaryArgument value, String next);

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.appliedcalls.AbstractArgumentsConformance.Binding")
    SecondaryArgument relabel(SecondaryArgument value, String next);
}

interface AbstractArgumentProject {
    @Contains PrimaryArgument getMain();
    @Contains SecondaryArgument getDetail();
    @Contains ArgumentCallHost getSkill();
}
