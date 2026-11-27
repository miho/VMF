package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Range;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType.Kind;
import org.junit.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.Assert.*;

@InterfaceOnly
interface NumericContract<T extends Number> {
    @DefaultValue("null") T getCurrent();
    List<T> getSamples();
    @Multiplicity(upper = 3) List<T> getBounded();
    VMap<String, T> getByName();
    VMap<T, Long> getByNumber();
    @Range(min = "0.1", max = "0.2", maxInclusive = false) T getThreshold();
}

@InterfaceOnly
interface NumericForward<N extends Number> extends NumericContract<N> { }

interface NumericDouble extends NumericForward<Double> { }

interface NumericLong extends NumericContract<Long> { }

@InterfaceOnly
interface NumericSlot<T extends Number> { T getValue(); }

interface NumericBigInteger extends NumericSlot<BigInteger> { }

@InterfaceOnly
interface NumericExecutableDefault<T extends Number> { @DefaultValue("-0.0d") T getValue(); }

interface NumericExecutableDouble extends NumericExecutableDefault<Double> { }

public class NumericGenericsTest {
    @Test
    public void csharpPreservesNumericForwardingAndExactClosedPropertyTypes() {
        var model = model(NumericContract.class, NumericForward.class, NumericDouble.class, NumericLong.class);
        CSharpCapabilities.validate(model);

        var contract = model.findType("NumericContract").orElseThrow();
        var parameter = contract.getTypeParameters().get(0);
        assertTrue(parameter.isNumberBound());
        assertEquals(Number.class.getName(), parameter.getBound().getExternalType());
        var forwarded = model.findType("NumericForward").orElseThrow().getSuperTypeApplications().get(0);
        assertEquals("N", forwarded.getArguments().get(0).getParameterName());

        var open = property(contract, "current");
        assertEquals(Kind.PARAMETER, open.getValueType().getKind());
        assertNull(open.getValueType().getModelType());
        assertTrue(open.hasDeclaredDefault());
        assertTrue(open.getScalarDefault().isEmpty());

        var floating = model.findType("NumericDouble").orElseThrow();
        var integral = model.findType("NumericLong").orElseThrow();
        assertEquals(Kind.FLOAT64, property(floating, "current").getValueType().getKind());
        assertFalse(property(floating, "current").getValueType().isPrimitive());
        assertEquals(Kind.INT64, property(integral, "current").getValueType().getKind());
        assertTrue(property(floating, "samples").allowsNullElements());
        assertFalse(property(floating, "bounded").allowsNullElements());
        assertEquals(3, property(floating, "bounded").getUpperBound());
        assertEquals(Kind.STRING, property(floating, "byName").getKeyType().getKind());
        assertEquals(Kind.FLOAT64, property(floating, "byName").getValueType().getKind());
        assertEquals(Kind.FLOAT64, property(floating, "byNumber").getKeyType().getKind());
        assertEquals(Kind.INT64, property(integral, "byNumber").getKeyType().getKind());
        assertEquals(Kind.INT64, property(floating, "byNumber").getValueType().getKind());
        assertEquals(1, property(contract, "threshold").getConstraints().size());
        assertEquals(1, property(floating, "threshold").getConstraints().size());

        var unsupported = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(unsupported.getMessage().contains(".NumericContract.T needs a single named model upper bound"));
        assertTrue(unsupported.getMessage().contains(".NumericContract.byNumber uses map properties"));
    }

    @Test
    public void csharpRejectsExternalNumericArgumentsAndNonNullOpenDefaults() {
        var unsupportedArgument = model(NumericSlot.class, NumericBigInteger.class);
        var typeFailure = assertThrows(IllegalArgumentException.class,
                () -> CSharpCapabilities.validate(unsupportedArgument));
        assertTrue(typeFailure.getMessage().contains(".NumericBigInteger has unsupported type arguments"));

        var unsupportedDefault = model(NumericExecutableDefault.class, NumericExecutableDouble.class);
        var defaultFailure = assertThrows(IllegalArgumentException.class,
                () -> CSharpCapabilities.validate(unsupportedDefault));
        assertTrue(defaultFailure.getMessage().contains(".NumericExecutableDefault.value uses declared default expressions"));
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static Property property(ResolvedModel.Type type, String name) {
        return type.getEffectiveProperties().stream()
                .filter(property -> property.getName().equals(name)).findFirst().orElseThrow();
    }
}
