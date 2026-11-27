package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType.Kind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@InterfaceOnly
interface DormantObjectBase {
    @GetterOnly Object getValue();
}

interface DormantIntegerBranch extends DormantObjectBase {
    Integer getValue();
}

interface DormantTextBranch extends DormantObjectBase {
    String getValue();
}

interface UnresolvedObjectBranch extends DormantObjectBase { }

interface UntypedObjectMembers {
    Object[] getValue();
}

public class DormantObjectGetterTest {
    @Test
    public void dormantBaseAllowsIndependentClosedScalarBranches() {
        var model = model(DormantObjectBase.class, DormantIntegerBranch.class, DormantTextBranch.class);
        CSharpCapabilities.validate(model);

        var base = value(model, "DormantObjectBase");
        assertEquals(Object.class.getName(), base.getValueType().getExternalType());
        assertTrue(base.isGetterOnly());
        var integer = value(model, "DormantIntegerBranch");
        assertEquals(Kind.INT32, integer.getValueType().getKind());
        assertFalse(integer.getValueType().isPrimitive());
        assertFalse(integer.isGetterOnly());
        assertEquals(Kind.STRING, value(model, "DormantTextBranch").getValueType().getKind());
    }

    @Test
    public void ordinaryUntypedMembersAreSupportedByTheCSharpProfile() {
        var declarations = model(UntypedObjectMembers.class);
        CSharpCapabilities.validate(declarations);
        var members = value(declarations, "UntypedObjectMembers");
        assertTrue(members.isCollection());
        assertTrue(members.isOrdered());
        assertFalse(members.isUnique());
        assertEquals(Object.class.getName(), members.getValueType().getExternalType());

        var baseline = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(declarations, "TypeScript"));
        assertTrue(baseline.getMessage().contains("UntypedObjectMembers.value has unsupported type java.lang.Object"));
    }

    @Test
    public void unresolvedObjectGetterBranchesAreDiagnosed() {
        var openBranch = assertThrows(IllegalArgumentException.class,
                () -> CSharpCapabilities.validate(model(DormantObjectBase.class, DormantIntegerBranch.class, UnresolvedObjectBranch.class)));
        assertTrue(openBranch.getMessage().contains("DormantObjectBase.value has unsupported type java.lang.Object"));
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static ResolvedModel.Property value(ResolvedModel model, String owner) {
        return model.findType(owner).orElseThrow().getEffectiveProperties().get(0);
    }
}
