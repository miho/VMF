package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

@InterfaceOnly
interface AbstractCallableRole {
    String getLabel();
}

interface AbstractCallablePrimary {
    String getId();
}

interface AbstractCallableToken extends AbstractCallablePrimary, AbstractCallableRole { }

interface AbstractCallableProvider {
    @DelegateTo(className = "example.ProviderBinding") AbstractCallableRole select();
}

interface AbstractCallableParameter {
    @DelegateTo(className = "example.ProviderBinding") void accept(AbstractCallableRole input);
}

interface AbstractCallableArray {
    @DelegateTo(className = "example.ProviderBinding") AbstractCallableRole[] select();
}

interface AbstractCallableList {
    @DelegateTo(className = "example.ProviderBinding") List<AbstractCallableRole> select();
}

@InterfaceOnly
interface GetterOnlyCallableRole {
    @GetterOnly String getLabel();
}

interface GetterOnlyCallableProvider {
    @DelegateTo(className = "example.ProviderBinding") GetterOnlyCallableRole select();
}

public class AbstractCallableReturnTest {
    @Test
    public void singularMutableAbstractReturnUsesTheNativeCompanion() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, AbstractCallableRole.class, AbstractCallablePrimary.class,
                AbstractCallableToken.class, AbstractCallableProvider.class, AbstractCallableList.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public IAbstractCallableRole? Select()"));
        assertTrue(source.contains("IAbstractCallableRole? Select(AbstractCallableProvider caller)"));
        assertTrue(source.contains("class AbstractCallableToken : AbstractCallablePrimary, IAbstractCallableRole"));
        assertTrue(source.contains("IList<IAbstractCallableRole?>? Select(AbstractCallableList caller)"));
    }

    @Test
    public void abstractArraysAndReadOnlyProjectionsStayOutsideTheReturnProfile() {
        rejects(AbstractCallableRole.class, AbstractCallableArray.class);
        rejects(GetterOnlyCallableRole.class, GetterOnlyCallableProvider.class);
    }

    private static void rejects(Class<?>... declarations) {
        var output = new MemoryResourceSet();
        var error = assertThrows(IllegalArgumentException.class,
                () -> VMF.generate(GenerationTarget.CSHARP, output, declarations));
        assertTrue(error.getMessage(), error.getMessage().contains("named mutable abstract model"));
        assertTrue(output.getMemSet().isEmpty());
    }
}
