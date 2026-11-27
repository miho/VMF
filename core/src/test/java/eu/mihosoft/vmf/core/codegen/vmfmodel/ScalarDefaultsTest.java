package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType.Kind;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

interface ScalarDefaultLiterals {
    @DefaultValue("true") boolean getEnabled();
    @DefaultValue(" false ") Boolean getBoxedEnabled();
    @DefaultValue("  -  0b100_0000 ") Byte getByteDefault();
    @DefaultValue("0_77") short getShortDefault();
    @DefaultValue("1__2_3") Integer getBoxedInt();
    @DefaultValue("-2_147_483_648") int getMinInt();
    @DefaultValue("0xffff_ffff") int getHexInt();
    @DefaultValue("-9_223_372_036_854_775_808L") Long getMinLong();
    @DefaultValue("0xffff_ffff_ffff_fffeL") long getHexLong();
    @DefaultValue("'\\377'") char getOctalChar();
    @DefaultValue("'\\uD800'") Character getSurrogateCodeUnit();
    @DefaultValue("65") Character getNarrowedChar();
    @DefaultValue("-0.0F") float getNegativeZero();
    @DefaultValue("0x0.0000_02p-126f") Float getSubnormal();
    @DefaultValue(" .25D ") Double getFraction();
    @DefaultValue("0.1f") double getWidenedFloat();
    @DefaultValue("\"before\\n\\141\\u0062\\\\u0063\\\"\\t\"") String getEscaped();
    @DefaultValue("\"\\uD83D\\uDE03\"") String getSupplementary();
}

interface DerivedScalarDefaults extends ScalarDefaultLiterals {}

interface DeclaredAndImplicitDefaults {
    @DefaultValue("null") String getText();
    @DefaultValue(" null ") Boolean getNullableFlag();
    @DefaultValue("null") long getPrimitiveZero();
    @DefaultValue("") char getPrimitiveNul();
    @DefaultValue("null") boolean getDisabled();
    String getImplicit();
}

interface UnsupportedDefaultDeclarations {
    @DefaultValue("String.valueOf(1)") String getExecutable();
    @DefaultValue("\"one\" + \"two\"") String getExpression();
    @DefaultValue("java.util.List.of(\"one\")") List<String> getCollection();
    @DefaultValue("2147483648") int getOverflow();
    @DefaultValue("0x_10") int getInvalidUnderscore();
    @DefaultValue("1L") Integer getIncompatibleWidth();
    @DefaultValue("1") Long getIncompatibleBoxedWiden();
    @DefaultValue("128") Byte getOverflowByte();
    @DefaultValue("1e-50f") float getUnderflowFloat();
    @DefaultValue("1e400") double getOverflowDouble();
    @DefaultValue(" null ") int getInvalidPrimitiveNull();
    @DefaultValue("\"\\s\"") String getUnsupportedEscape();
    @DefaultValue("\"\\u000a\"") String getUnicodeLineBreak();
}

@Immutable
interface ImmutableScalarDefaults {
    @DefaultValue("27") Integer getNumber();
}

interface UnpairedStringDefault {
    @DefaultValue("\"\\uD800\"") String getText();
}

public class ScalarDefaultsTest {
    @Test
    public void typedLiteralsPreserveJavaAssignmentAndInheritedDefaults() {
        var model = model(ScalarDefaultLiterals.class, DerivedScalarDefaults.class);
        CSharpCapabilities.validate(model);
        var base = model.findType("ScalarDefaultLiterals").orElseThrow();
        check(base, "enabled", Kind.BOOLEAN, true);
        check(base, "boxedEnabled", Kind.BOOLEAN, false);
        check(base, "byteDefault", Kind.INT8, (byte) -64);
        check(base, "shortDefault", Kind.INT16, (short) 63);
        check(base, "boxedInt", Kind.INT32, 123);
        check(base, "minInt", Kind.INT32, Integer.MIN_VALUE);
        check(base, "hexInt", Kind.INT32, -1);
        check(base, "minLong", Kind.INT64, Long.MIN_VALUE);
        check(base, "hexLong", Kind.INT64, -2L);
        check(base, "octalChar", Kind.CHAR, (char) 255);
        check(base, "surrogateCodeUnit", Kind.CHAR, (char) 0xD800);
        check(base, "narrowedChar", Kind.CHAR, 'A');
        check(base, "negativeZero", Kind.FLOAT32, -0.0f);
        check(base, "subnormal", Kind.FLOAT32, Float.MIN_VALUE);
        check(base, "fraction", Kind.FLOAT64, 0.25d);
        check(base, "widenedFloat", Kind.FLOAT64, (double) 0.1f);
        check(base, "escaped", Kind.STRING, "before\nab\\u0063\"\t");
        check(base, "supplementary", Kind.STRING, "\uD83D\uDE03");
        check(model.findType("DerivedScalarDefaults").orElseThrow(), "hexLong", Kind.INT64, -2L);
    }

    @Test
    public void absentAndExplicitNullDefaultsKeepDistinctMetadata() {
        var declared = model(DeclaredAndImplicitDefaults.class).findType("DeclaredAndImplicitDefaults").orElseThrow();
        check(declared, "text", Kind.STRING, null);
        check(declared, "nullableFlag", Kind.BOOLEAN, null);
        check(declared, "primitiveZero", Kind.INT64, 0L);
        check(declared, "primitiveNul", Kind.CHAR, '\0');
        check(declared, "disabled", Kind.BOOLEAN, false);
        assertFalse(property(declared, "implicit").hasDeclaredDefault());
        assertTrue(property(declared, "implicit").getScalarDefault().isEmpty());
    }

    @Test
    public void JavaExpressionsAndInvalidDeclarationsAreStillGated() {
        var unsupported = model(UnsupportedDefaultDeclarations.class);
        for (Property property : unsupported.getTypes().get(0).getProperties()) {
            assertTrue(property.getName(), property.hasDeclaredDefault());
            assertTrue(property.getName(), property.getScalarDefault().isEmpty());
        }
        var csharp = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(unsupported));
        assertTrue(csharp.getMessage(), csharp.getMessage().contains(".executable uses declared default expressions"));
        var typescript = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(model(ScalarDefaultLiterals.class), "TypeScript"));
        assertTrue(typescript.getMessage(), typescript.getMessage().contains(".enabled uses declared default expressions"));
    }

    @Test
    public void csharpAcceptsImmutableLiteralsAndRejectsNonportableStringDefaults() {
        CSharpCapabilities.validate(model(ImmutableScalarDefaults.class));
        var unpaired = assertThrows(IllegalArgumentException.class,
                () -> CSharpCapabilities.validate(model(UnpairedStringDefault.class)));
        assertTrue(unpaired.getMessage(), unpaired.getMessage().contains(".text has a string default with an unpaired UTF-16 surrogate"));
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static Property property(ResolvedModel.Type type, String name) {
        return type.getEffectiveProperties().stream().filter(property -> property.getName().equals(name)).findFirst().orElseThrow();
    }

    private static void check(ResolvedModel.Type type, String propertyName, Kind kind, Object value) {
        var property = property(type, propertyName);
        assertTrue(propertyName, property.hasDeclaredDefault());
        var declared = property.getScalarDefault().orElseThrow();
        assertEquals(propertyName, kind, declared.getKind());
        assertEquals(propertyName, value, declared.getValue());
        assertEquals(propertyName, value == null, declared.isNull());
    }
}
