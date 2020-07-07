package eu.mihosoft.vmftest.externaltypes;

import eu.mihosoft.vmf.runtime.core.DelegatedBehaviorBase;


public class ModelBehavior extends DelegatedBehaviorBase<Model> {

    public void runAction(MyAction action) {
        System.out.println("run action: ");
        action.accept(getCaller());
    }

}
