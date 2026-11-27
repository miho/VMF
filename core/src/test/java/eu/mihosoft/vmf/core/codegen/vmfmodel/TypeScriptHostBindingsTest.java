package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

@ExternalType(pkgName = "example.hosts")
@Annotation(key = "vmf:typescript:external-type", value = "\"LOW\" | \"HIGH\"")
interface HostLevel {
}

@ExternalType(pkgName = "example.hosts")
@Annotation(key = "vmf:typescript:external-type", value = "HostImage")
@Annotation(key = "vmf:typescript:external-module", value = "./hosts.js")
interface HostImage {
}

@ExternalType(pkgName = "example.hosts")
@Annotation(key = "vmf:typescript:external-type", value = "Date")
interface HostDate {
}

@ExternalType(pkgName = "example.hosts")
@Annotation(key = "vmf:typescript:external-type", value = "not a type")
interface HostBroken {
}

interface HostedValues {
    @DefaultValue("HostLevel.HIGH")
    HostLevel getLevel();

    HostLevel[] getHistory();

    java.util.UUID getId();

    Object getPayload();

    HostImage getImage();

    @DefaultValue("example.Clock.now()")
    @Annotation(key = "vmf:typescript:bind-default", value = "true")
    HostDate getCreated();
}

interface HostedBroken {
    HostBroken getValue();
}

interface HostedMisplacedBinding {
    @DefaultValue("3")
    @Annotation(key = "vmf:typescript:bind-default", value = "true")
    int getCount();
}

@DelegateTo(className = "example.HostBehavior")
interface HostedBehavior {
    @DefaultValue("7")
    int getStart();

    @DelegateTo(className = "example.HostBehavior")
    int add(int amount);

    @DelegateTo(className = "example.HostBehavior")
    int add(int amount, int more);

    @DelegateTo(className = "example.HostBehavior")
    HostImage render(HostLevel level, Object hint);
}

@InterfaceOnly
interface HostedRole {
    String describe();
}

@DelegateTo(className = "example.RoleBehavior")
interface HostedRoleImplementation extends HostedRole {
    @Override
    String describe();
}

interface HostedSameArity {
    @DelegateTo(className = "example.ScaleBehavior")
    int scale(int value);

    @DelegateTo(className = "example.ScaleBehavior")
    Integer scale(Integer value);
}

interface HostedPlain {
    String getName();
}

public class TypeScriptHostBindingsTest {

    @Test
    public void externalTypesDefaultsAndCodecsBecomeModelBindings() throws IOException {
        String source = generate(HostLevel.class, HostImage.class, HostDate.class, HostedValues.class);
        assertTrue(source.contains("import type { HostImage } from \"./hosts.js\";"));
        assertTrue(source.contains("export type HostLevel = \"LOW\" | \"HIGH\";"));
        // A default that names a literal of the union needs no binding.
        assertTrue(source.contains("__vmfValue_level: HostLevel | null = \"HIGH\";"));
        assertTrue(source.contains("readonly HostImage: vmf.ExternalCodec<HostImage>;"));
        assertTrue(source.contains("readonly created: () => Date | null;"));
        assertTrue(source.contains("__vmfBindings.use(\"defaults.HostedValues.created\", bindings => bindings.defaults.HostedValues.created)()"));
        assertTrue(source.contains("[\"externals.HostDate\", \"externals.HostImage\", \"defaults.HostedValues.created\"]"));
        assertTrue(source.contains("export function installModelBindings(bindings: ModelBindings): void {"));
        // UUIDs are canonical strings; untyped values are unknown.
        assertTrue(source.contains("vmf.setValue(this, \"id\", vmf.canonicalUuid(value));"));
        assertTrue(source.contains("scalarType: \"uuid\""));
        assertTrue(source.contains("scalarType: \"object\""));
        assertTrue(source.contains("literals: [\"LOW\", \"HIGH\"], writeNull: true"));
    }

    @Test
    public void modelsWithoutHostCodeGenerateNoBindings() throws IOException {
        String source = generate(HostedPlain.class);
        assertFalse(source.contains("ModelBindings"));
        assertFalse(source.contains("installModelBindings"));
    }

    @Test
    public void delegatedMethodsCallThePerObjectBehavior() throws IOException {
        String source = generate(HostLevel.class, HostImage.class, HostedBehavior.class);
        assertTrue(source.contains("export interface HostedBehaviorBehavior {"));
        assertTrue(source.contains("onHostedBehaviorInstantiated?(caller: HostedBehavior): void;"));
        assertTrue(source.contains("render(caller: HostedBehavior, arg0: HostLevel | null, arg1: unknown): HostImage | null;"));
        assertTrue(source.contains("readonly HostedBehavior: () => HostedBehaviorBehavior;"));
        // The construction hook runs before the declared defaults, as in Java.
        assertTrue(source.contains("__vmfValue_start: number = 0;"));
        int hook = source.indexOf(".onHostedBehaviorInstantiated?.(this);");
        int defaults = source.indexOf("this.__vmfApplyDefaults();");
        assertTrue(hook > 0 && defaults > hook);
        assertTrue(source.contains("this.__vmfValue_start = 7;"));
        // Overloads with different numbers of parameters share one method.
        assertTrue(source.contains("add(arg0: number, arg1: number): number;"));
        assertTrue(source.contains("switch (args.length) {"));
        assertTrue(source.contains("replaceBehavior(behavior: HostedBehaviorBehavior): void {"));
        assertTrue(source.contains("vmf.declareBehavior(HostedBehavior, [\"add\", \"render\"]);"));
    }

    @Test
    public void interfaceOnlyMethodsWithoutBindingThrowUnlessReplaced() throws IOException {
        String source = generate(HostedRole.class, HostedRoleImplementation.class);
        assertTrue(source.contains("vmf.unboundBehavior(\"HostedRole.describe\")"));
        assertTrue(source.contains("override describe(): string | null {"));
        assertTrue(source.contains("readonly HostedRoleImplementation: () => HostedRoleImplementationBehavior;"));
        assertFalse(source.contains("readonly HostedRole: "));
    }

    @Test
    public void rejectsWhatTypeScriptCannotBind() {
        var sameArity = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(model(HostedSameArity.class), "TypeScript"));
        assertTrue(sameArity.getMessage().contains("HostedSameArity.scale needs overloads that differ in their number"));

        var broken = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(model(HostBroken.class, HostedBroken.class), "TypeScript"));
        assertTrue(broken.getMessage().contains("HostedBroken.value maps example.hosts.HostBroken to 'not a type'"));

        var misplaced = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(model(HostedMisplacedBinding.class), "TypeScript"));
        assertTrue(misplaced.getMessage().contains("HostedMisplacedBinding.count needs a non-literal scalar @DefaultValue"));
    }

    private static String generate(Class<?>... declarations) throws IOException {
        var output = new MemoryResourceSet();
        VMF.generate(GenerationTarget.TYPESCRIPT, output, declarations);
        return output.getMemSet().elements().nextElement().asString();
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }
}
