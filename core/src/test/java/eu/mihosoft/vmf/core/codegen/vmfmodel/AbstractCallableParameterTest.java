package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@InterfaceOnly
interface PrimaryCallableArgument { String getName(); }

interface PrimaryCallableArgumentValue extends PrimaryCallableArgument { }

interface PrimaryCallableArgumentHost {
    @DelegateTo(className = "example.Binding") PrimaryCallableArgument echo(PrimaryCallableArgument value);
}

interface AbstractCallableArrayArgument {
    @DelegateTo(className = "example.Binding") void accept(AbstractCallableRole[] values);
}

interface GetterOnlyCallableArgument {
    @DelegateTo(className = "example.Binding") void accept(GetterOnlyCallableRole value);
}

public class AbstractCallableParameterTest {
    @Test
    public void singularMutableAbstractParametersUseThePrimaryClassAndCompanion() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, PrimaryCallableArgument.class, PrimaryCallableArgumentValue.class,
                PrimaryCallableArgumentHost.class, AbstractCallableRole.class, AbstractCallablePrimary.class,
                AbstractCallableToken.class, AbstractCallableParameter.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public PrimaryCallableArgument? Echo(PrimaryCallableArgument? "));
        assertTrue(source.contains("PrimaryCallableArgument? Echo(PrimaryCallableArgumentHost caller, PrimaryCallableArgument? "));
        assertTrue(source.contains("public void Accept(IAbstractCallableRole? "));
        assertTrue(source.contains("void Accept(AbstractCallableParameter caller, IAbstractCallableRole? "));
    }

    @Test
    public void abstractArraysAndGetterOnlyArgumentsRemainUnsupported() {
        rejects(AbstractCallableRole.class, AbstractCallableArrayArgument.class);
        rejects(GetterOnlyCallableRole.class, GetterOnlyCallableArgument.class);
    }

    private static void rejects(Class<?>... declarations) {
        var output = new MemoryResourceSet();
        var error = assertThrows(IllegalArgumentException.class,
                () -> VMF.generate(GenerationTarget.CSHARP, output, declarations));
        assertTrue(error.getMessage(), error.getMessage().contains("needs portable scalars"));
        assertTrue(output.getMemSet().isEmpty());
    }
}
