package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vmf.runtime.core.*;

import java.util.List;

public class DiffingImpl implements Diffing {
    private VObject sourceObject;
    private VObject targetObject;

    public void init(VObject target){

        targetObject = target;
        InitChanges.init(this.sourceObject, targetObject);
    }

    public void setSource(VObject sObj){
        sourceObject = sObj;
    }

    public void printChanges(){
        MakeChanges.printChanges();
    }

    public void applyChanges(){
        MakeChanges.applyChange();
    }

    public List<Change> getChanges(){
        return MakeChanges.getChanges();
    }
}
