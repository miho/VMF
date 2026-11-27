package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType.Kind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@ExternalType(pkgName = "example.host")
@Annotation(key = "vmf:csharp:external-value-type", value = "Example.Host.ProbeEnum")
interface ProbeEnum { }

@ExternalType(pkgName = "java.lang.Thread")
@Annotation(key = "vmf:csharp:external-value-type", value = "Example.Host.ThreadState")
interface State { }

@InterfaceOnly
interface ExternalValueSamples<T> {
    T getLatest();
    List<T> getSamples();
}

interface BoundExternalSamples extends ExternalValueSamples<ProbeEnum> { }

interface UnboundExternalSamples extends ExternalValueSamples<Thread.State> { }

public class ExternalGenericsTest {
    @Test
    public void csharpAdmitsDeclaredExternalBindingsAndProjectsClosedFields() {
        var model = project(ProbeEnum.class, ExternalValueSamples.class, BoundExternalSamples.class);
        CSharpCapabilities.validate(model);
        var closed = model.findType("BoundExternalSamples").orElseThrow();
        var parentArgument = closed.getSuperTypeApplications().get(0).getArguments().get(0);
        assertEquals(Kind.EXTERNAL, parentArgument.getKind());
        assertEquals("example.host.ProbeEnum", parentArgument.getExternalType());
        assertTrue(closed.getEffectiveProperties().stream().allMatch(property ->
                property.getValueType().getKind() == Kind.EXTERNAL
                        && parentArgument.getExternalType().equals(property.getValueType().getExternalType())));
    }

    @Test
    public void directNestedExternalArgumentsUseTheCanonicalBindingName() {
        var model = project(State.class, ExternalValueSamples.class, UnboundExternalSamples.class);
        CSharpCapabilities.validate(model);
        var type = model.findType("UnboundExternalSamples").orElseThrow();
        var argument = type.getSuperTypeApplications().get(0).getArguments().get(0);
        assertEquals("java.lang.Thread.State", argument.getExternalType());
        assertTrue(type.getEffectiveProperties().stream().allMatch(property ->
                argument.getExternalType().equals(property.getValueType().getExternalType())));
    }

    @Test
    public void csharpDiagnosesUnboundExternalArguments() {
        var model = project(ExternalValueSamples.class, UnboundExternalSamples.class);
        var failure = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(model));
        assertTrue(failure.getMessage().contains(".UnboundExternalSamples has unsupported type arguments"));
    }

    private static ResolvedModel project(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }
}
