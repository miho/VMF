package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

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

interface TsDefaultSeed {
    String getName();
}

@Immutable
interface TsFrozenSeed {
    String getName();
}

@DelegateTo(className = "example.DefaultsBehavior")
interface TsCollectionDefaults {
    @DefaultValue("java.util.List.of(\"a\", \"b\")")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    List<String> getStages();

    @DefaultValue("java.util.Set.of(2L)")
    Set<Long> getCodes();

    @DefaultValue("java.util.Map.of(\"port\", 80)")
    VMap<String, Integer> getPorts();

    @DefaultValue("java.util.List.of(TsDefaultSeed.newBuilder().withName(\"first\").build())")
    Set<TsDefaultSeed> getSeeds();

    @Multiplicity(upper = 3)
    @DefaultValue("example.Defaults.numbers()")
    @Annotation(key = "vmf:typescript:bind-default", value = "true")
    List<Integer> getNumbers();
}

@Immutable
interface TsFrozenCollectionDefaults {
    @DefaultValue("java.util.List.of(TsFrozenSeed.newBuilder().withName(\"first\").build())")
    Set<TsFrozenSeed> getSeeds();

    @DefaultValue("java.util.Map.of(4, \"ready\")")
    VMap<Integer, String> getChoices();
}

interface TsDefaultBase {
    @DefaultValue("java.util.List.of(\"x\")")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    List<String> getTags();
}

interface TsDefaultRedeclaration extends TsDefaultBase {
    @Override
    @DefaultValue("java.util.List.of(\"y\")")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    List<String> getTags();
}

interface TsLeftRole {
    @DelegateTo(className = "example.Sides")
    int left();
}

interface TsRightRole {
    @DelegateTo(className = "example.Sides")
    int right();
}

interface TsBothRoles extends TsLeftRole, TsRightRole {
}

interface TsMixedBase {
    @DelegateTo(className = "example.First")
    int first();
}

interface TsMixedRole extends TsMixedBase {
    @DelegateTo(className = "example.Second")
    int second();

    @DelegateTo(className = "example.Third")
    int third();
}

interface TsMixedOwner extends TsLeftRole, TsMixedRole {
}

interface TsPlainCaption {
    String getLabel();
}

interface TsCaption extends TsPlainCaption {
    @Override
    @DelegateTo(className = "example.Caption")
    String toString();
}

interface TsBoundBase {
    @DefaultValue("example.Labels.base()")
    @Annotation(key = "vmf:typescript:bind-default", value = "true")
    String getLabel();
}

interface TsBoundRedeclaration extends TsBoundBase {
    @Override
    @DefaultValue("example.Labels.derived()")
    @Annotation(key = "vmf:typescript:bind-default", value = "true")
    String getLabel();
}

interface TsNullableBoundDefault {
    @DefaultValue("example.Defaults.numbers()")
    @Annotation(key = "vmf:typescript:bind-default", value = "true")
    List<Integer> getNumbers();
}

public class TypeScriptHostBindingsTest {

    @Test
    public void externalTypesDefaultsAndCodecsBecomeModelBindings() throws IOException {
        String source = generate(HostLevel.class, HostImage.class, HostDate.class, HostedValues.class);
        assertTrue(source.contains("import type { HostImage } from \"./hosts.js\";"));
        assertTrue(source.contains("export type HostLevel = \"LOW\" | \"HIGH\";"));
        // A default that names a literal of the union needs no binding. With a bound default in the class, the
        // constructor applies the defaults, each once.
        assertTrue(source.contains("__vmfValue_level: HostLevel | null = null;"));
        assertTrue(source.contains("this.__vmfValue_level = \"HIGH\";"));
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
    public void collectionDefaultsReplaceWhatTheHookAdded() throws IOException {
        String source = generate(TsDefaultSeed.class, TsCollectionDefaults.class);
        assertTrue(source.contains("this.stages.clear();\n        this.stages.addAll([\"a\", \"b\"]);"));
        assertTrue(source.contains("this.codes.addAll([2n]);"));
        assertTrue(source.contains("this.ports.putAll([[\"port\", 80]]);"));
        assertTrue(source.contains("this.seeds.add(Object.assign(new TsDefaultSeed(), { name: \"first\" }));"));
        assertTrue(source.contains("this.numbers.addAll(__vmfBindings.use(\"defaults.TsCollectionDefaults.numbers\","
                + " bindings => bindings.defaults.TsCollectionDefaults.numbers)());"));
        assertTrue(source.contains("readonly numbers: () => Iterable<number>;"));
        // The construction hook runs first; a field in a document replaces the default.
        int hook = source.indexOf(".onTsCollectionDefaultsInstantiated?.(this);");
        int defaults = source.indexOf("this.__vmfApplyDefaults();");
        assertTrue(hook > 0 && defaults > hook);
        assertTrue(source.contains("modelType: () => TsDefaultSeed, hasDefault: true"));

        String frozen = generate(TsFrozenSeed.class, TsFrozenCollectionDefaults.class);
        assertTrue(frozen.contains("values.seeds ?? [new TsFrozenSeed({ name: \"first\" })]"));
        assertTrue(frozen.contains("values.choices ?? [[4, \"ready\"]]"));
    }

    @Test
    public void aRedeclaredBoundDefaultEvaluatesOnlyTheEffectiveBinding() throws IOException {
        String source = generate(TsBoundBase.class, TsBoundRedeclaration.class);
        int derived = source.indexOf("export class TsBoundRedeclaration");
        String body = source.substring(derived);
        assertTrue(body, body.contains("override __vmfApplyDefaults(): void {\n        this.__vmfValue_label ="
                + " __vmfBindings.use(\"defaults.TsBoundRedeclaration.label\""));
        assertFalse(body, body.contains("super.__vmfApplyDefaults();"));
        assertFalse(body, body.contains("defaults.TsBoundBase.label"));
    }

    @Test
    public void classesDeclareTheMethodsOfBehaviorRolesTheyImplement() throws IOException {
        String source = generate(TsLeftRole.class, TsRightRole.class, TsBothRoles.class);
        assertTrue(source.contains("export class TsBothRoles extends TsLeftRole implements TsRightRole {"));
        // The method of the implemented role calls the behavior of the type that declares it.
        assertTrue(source.contains("return vmf.behaviorOf<TsRightRoleBehavior>(this, \"TsRightRole\","
                + " __vmfBehavior_TsRightRole).right(this);"));
        // A replacement implements the behaviors of the base class and of the implemented role.
        assertTrue(source.contains("override replaceBehavior(behavior: TsLeftRoleBehavior & TsRightRoleBehavior): void {"));

        // A class with a base class overrides Object.prototype.toString.
        String caption = generate(TsPlainCaption.class, TsCaption.class);
        assertTrue(caption.contains("override toString(): string | null {"));
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
        assertTrue(misplaced.getMessage().contains("HostedMisplacedBinding.count needs a non-literal @DefaultValue"));

        var nullable = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(model(TsNullableBoundDefault.class), "TypeScript"));
        assertTrue(nullable.getMessage(), nullable.getMessage().contains("TsNullableBoundDefault.numbers needs a"
                + " non-literal @DefaultValue of a scalar, a list or set of non-null portable scalars"));

        // As in C#, an implemented supertype contributes data or the behavior of a bound behavior role.
        var mixed = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(
                model(TsLeftRole.class, TsMixedBase.class, TsMixedRole.class, TsMixedOwner.class), "TypeScript"));
        assertTrue(mixed.getMessage(), mixed.getMessage().contains("TsMixedOwner needs a data-only secondary supertype"
                + " or a bound behavior role"));

        // A redeclaration would give subclasses a second default.
        var redeclared = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(model(TsDefaultBase.class, TsDefaultRedeclaration.class), "TypeScript"));
        assertTrue(redeclared.getMessage(), redeclared.getMessage().contains("TsDefaultBase.tags uses declared default"
                + " expressions: TypeScript generates a collection default on a concrete nongeneric declaration"));
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
