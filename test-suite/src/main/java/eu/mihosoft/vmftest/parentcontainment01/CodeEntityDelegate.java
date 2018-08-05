package eu.mihosoft.vmftest.parentcontainment01;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;
import vjavax.observer.Subscription;

import java.util.List;
import java.util.stream.Collectors;

public class CodeEntityDelegate implements DelegatedBehavior<CodeEntity> {

    private CodeEntity codeEntity;

    @Override
    public void setCaller(CodeEntity caller) {
        this.codeEntity = caller;
    }

    public void onCodeEntityInstantiated() {
        codeEntity.vmf().changes().addListener( l -> {

            if(l.object() != codeEntity || "parent".equals(l.propertyName())) {
                return;
            }

            Object o = l.propertyChange().get().newValue();

            if(o instanceof CodeEntity) {
                CodeEntity cE = (CodeEntity) o;
                cE.setParent(codeEntity);
            }

        }, false);
    }

    public CodeEntity root() {

        CodeEntity cE = codeEntity;

        while(cE.getParent()!=null) {
            cE = cE.getParent();
        }

        return cE;

    }
}
