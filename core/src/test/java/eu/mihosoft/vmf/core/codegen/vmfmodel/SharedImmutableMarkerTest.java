package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

@InterfaceOnly
interface SharedEntryMarker { }

@Immutable
interface FrozenMarkerEntry extends SharedEntryMarker {
    String getValue();
}

interface MutableMarkerEntry extends SharedEntryMarker {
    String getTitle();
}

interface MarkerShelf {
    @Contains MutableMarkerEntry getOwned();
    SharedEntryMarker[] getValues();
}

interface UnsafeMixedMarkerOwner {
    @Contains SharedEntryMarker[] getOwned();
}

@InterfaceOnly
interface MutableOnlyMarker { }

interface MutableOnlyEntry extends MutableOnlyMarker { }

public class SharedImmutableMarkerTest {
    @Test
    public void aSharedEmptyMarkerUsesTheReadOnlyContract() throws IOException {
        Class<?>[] declarations = {SharedEntryMarker.class, FrozenMarkerEntry.class, MutableMarkerEntry.class,
                MarkerShelf.class};
        var model = ResolvedModel.from(Model.newInstance(declarations), declarations);
        assertTrue(model.findType("SharedEntryMarker").orElseThrow().isGetterOnlyTrait());
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, declarations);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("public sealed class FrozenMarkerEntry : IReadOnlySharedEntryMarker"));
        assertTrue(source.contains("public abstract class SharedEntryMarker : IReadOnlySharedEntryMarker"));
        assertTrue(source.contains("ReferenceList<IReadOnlySharedEntryMarker> Values"));
    }

    @Test
    public void aGetterRoleWithAnImmutableImplementationStillCannotBeAnOwningTarget() {
        var error = assertThrows(IllegalArgumentException.class, () -> VMF.generate(GenerationTarget.CSHARP,
                new MemoryResourceSet(), SharedEntryMarker.class, FrozenMarkerEntry.class, MutableMarkerEntry.class,
                UnsafeMixedMarkerOwner.class));
        assertTrue(error.getMessage().contains("UnsafeMixedMarkerOwner.owned cannot use a getter-only trait as a containment target"));
    }

    @Test
    public void anEmptyMutableOnlyContractKeepsItsExistingProjection() {
        Class<?>[] declarations = {MutableOnlyMarker.class, MutableOnlyEntry.class};
        var model = ResolvedModel.from(Model.newInstance(declarations), declarations);
        assertFalse(model.findType("MutableOnlyMarker").orElseThrow().isGetterOnlyTrait());
    }
}
