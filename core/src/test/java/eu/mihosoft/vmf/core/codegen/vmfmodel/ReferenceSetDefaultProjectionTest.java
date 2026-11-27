package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType.Kind;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

interface FreshReferenceSeed { String getName(); }

interface ReferenceSeedHeader { int getRevision(); }

interface CompositeReferenceSeed extends ReferenceSeedHeader, FreshReferenceSeed { }

@Immutable
interface ImmutableReferenceSeed { String getName(); }

interface FreshReferenceDefaults {
    @DefaultValue("java.util.List.of(FreshReferenceSeed.newInstance())")
    Set<FreshReferenceSeed> getValues();
}

interface DerivedReferenceDefaults extends FreshReferenceDefaults { }

interface MixedDerivedReferenceDefaults extends FreshReferenceDefaults {
    @DefaultValue("String.valueOf(42)") @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getLabel();

    @Multiplicity(upper = 3)
    @DefaultValue("java.util.List.copyOf(java.util.List.of(1))") @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();
}

interface RedefinedMixedManagedDefaults extends MixedDerivedReferenceDefaults {
    @Override List<Integer> getNumbers();
}

interface RedefinedReferenceDefaults extends FreshReferenceDefaults {
    @Override Set<FreshReferenceSeed> getValues();
}

@Immutable
interface ImmutableReferenceDefaults {
    @DefaultValue("java.util.List.of(ImmutableReferenceSeed.newBuilder().withName(\"escaped\\ntext\").build())")
    Set<ImmutableReferenceSeed> getValues();
}

interface ExecutableReferenceDefaults {
    @DefaultValue("java.util.List.of(FreshReferenceSeed.newBuilder().withName(System.getProperty(\"seed\")).build())")
    Set<FreshReferenceSeed> getValues();
}

public class ReferenceSetDefaultProjectionTest {
    @Test
    public void projectsFreshConstructionAndTypedStringAssignments() {
        var model = model(FreshReferenceSeed.class, FreshReferenceDefaults.class,
                ImmutableReferenceSeed.class, ImmutableReferenceDefaults.class);
        CSharpCapabilities.validate(model);

        var fresh = member(model, "FreshReferenceDefaults").getReferenceSetDefault().orElseThrow();
        assertEquals("FreshReferenceSeed", fresh.getModelType());
        assertTrue(fresh.getAssignments().isEmpty());

        var immutable = member(model, "ImmutableReferenceDefaults").getReferenceSetDefault().orElseThrow();
        assertEquals("ImmutableReferenceSeed", immutable.getModelType());
        var value = immutable.getAssignments().get("name");
        assertEquals(Kind.STRING, value.getKind());
        assertEquals("escaped\ntext", value.getValue());
    }

    @Test
    public void unchangedReferenceDefaultIsAvailableInConcreteCSharpInheritance() {
        var inherited = model(FreshReferenceSeed.class, FreshReferenceDefaults.class, DerivedReferenceDefaults.class);
        CSharpCapabilities.validate(inherited);
        assertEquals("FreshReferenceDefaults", member(inherited, "DerivedReferenceDefaults").getDeclaringType());
        assertTrue(member(inherited, "DerivedReferenceDefaults").getReferenceSetDefault().isPresent());
        // TypeScript subclasses inherit the unchanged default too.
        PortableCapabilities.validate(inherited, "TypeScript");

        var redeclared = model(FreshReferenceSeed.class, FreshReferenceDefaults.class, RedefinedReferenceDefaults.class);
        assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(redeclared));
        var typescript = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(redeclared, "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains("FreshReferenceDefaults.values uses declared"
                + " default expressions: TypeScript generates a collection default on a concrete nongeneric declaration"));
    }

    @Test
    public void csharpAcceptsUnchangedManagedSlotsAndExecutableValuesWithInheritedReferences() {
        var inherited = model(FreshReferenceSeed.class, FreshReferenceDefaults.class, MixedDerivedReferenceDefaults.class);
        CSharpCapabilities.validate(inherited);
        assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(inherited, "TypeScript"));
        var redeclared = model(FreshReferenceSeed.class, FreshReferenceDefaults.class,
                MixedDerivedReferenceDefaults.class, RedefinedMixedManagedDefaults.class);
        assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(redeclared));
    }

    @Test
    public void arbitraryJavaCallsRetainTheExplicitBindingRequirement() {
        var model = model(FreshReferenceSeed.class, ExecutableReferenceDefaults.class);
        assertTrue(member(model, "ExecutableReferenceDefaults").getReferenceSetDefault().isEmpty());
        var failure = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(model));
        assertTrue(failure.getMessage().contains("ExecutableReferenceDefaults.values uses declared default expressions"));
    }

    @Test
    public void typedDefaultConstructsTheClassBehindACompanionContract() throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.CSHARP, output, FreshReferenceSeed.class, ReferenceSeedHeader.class,
                CompositeReferenceSeed.class, FreshReferenceDefaults.class);
        String source = output.getMemSet().elements().nextElement().asString();
        assertTrue(source.contains("new IFreshReferenceSeed[] { new FreshReferenceSeed() }"));
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static ResolvedModel.Property member(ResolvedModel model, String type) {
        return model.findType(type).orElseThrow().getEffectiveProperties().get(0);
    }
}
