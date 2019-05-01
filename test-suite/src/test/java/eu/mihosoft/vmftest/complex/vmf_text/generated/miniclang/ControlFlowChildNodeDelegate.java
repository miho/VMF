package eu.mihosoft.vmftest.complex.vmf_text.generated.miniclang;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;

public class ControlFlowChildNodeDelegate implements DelegatedBehavior<VObject> {
    private VObject obj;

    @Override
    public void setCaller(VObject caller) {
        this.obj = obj;
    }

    public VList<ControlFlowScope> parentScopes() {
        return null;
    }
}