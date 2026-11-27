/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Length;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Pattern;
import eu.mihosoft.vmf.core.PropertyConstraint;
import eu.mihosoft.vmf.core.Range;
import eu.mihosoft.vmf.core.Required;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType.Kind;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

interface PortableScalars {
    boolean getBoolean();
    Boolean getBoxedBoolean();
    byte getByte();
    Byte getBoxedByte();
    short getShort();
    Short getBoxedShort();
    int getInt();
    Integer getBoxedInt();
    long getLong();
    Long getBoxedLong();
    char getChar();
    Character getBoxedChar();
    float getFloat();
    Float getBoxedFloat();
    double getDouble();
    Double getBoxedDouble();
    String getText();
    @Required Integer getRequiredBoxedInt();
}

interface RangeAndText {
    @Range(min = "+09007199254740993", minInclusive = false, max = "9007199254740999")
    long getCounter();

    @Length(min = 1, max = 4)
    @Pattern("^(?:[A-Z]\\d?)$")
    String getLabel();
}

interface SpecializedRangeAndText extends RangeAndText {}

interface JavaPattern {
    @Pattern(value = "(?i)abc", portable = false)
    String getText();
}

interface ConflictingPattern extends JavaPattern {
    @Override
    @Pattern(value = "(?i)abc", portable = false)
    @Annotation(key = "vmf:pattern:csharp", value = "(?i)abc")
    @Annotation(key = "vmf:pattern:csharp", value = "abc")
    String getText();
}

interface RepeatedLength extends RangeAndText {
    @Override
    @Length(min = 1, max = 4)
    String getLabel();
}

public class PortableScalarsTest {
    @Test
    public void wideScalarKindsPreservePrimitiveAndBoxedTypes() {
        ResolvedModel model = model(PortableScalars.class);
        CSharpCapabilities.validate(model);
        assertScalarPair(model, "Boolean", Kind.BOOLEAN);
        assertScalarPair(model, "Byte", Kind.INT8);
        assertScalarPair(model, "Short", Kind.INT16);
        assertScalarPair(model, "Int", Kind.INT32);
        assertScalarPair(model, "Long", Kind.INT64);
        assertScalarPair(model, "Char", Kind.CHAR);
        assertScalarPair(model, "Float", Kind.FLOAT32);
        assertScalarPair(model, "Double", Kind.FLOAT64);
        assertEquals(Kind.STRING, property(model, "PortableScalars", "text").getValueType().getKind());
        var required = property(model, "PortableScalars", "requiredBoxedInt");
        assertTrue(required.isRequired());
        assertFalse(required.getValueType().isPrimitive());

        // T6: TypeScript maps every portable scalar, boxed char included.
        PortableCapabilities.validate(model, "TypeScript");
    }

    @Test
    public void constraintMetadataRetainsWirePrecisionAndInheritedOrder() {
        ResolvedModel model = model(RangeAndText.class, SpecializedRangeAndText.class);
        CSharpCapabilities.validate(model);

        Property inheritedCounter = property(model, "SpecializedRangeAndText", "counter");
        PropertyConstraint range = inheritedCounter.getConstraints().get(0);
        assertEquals(PropertyConstraint.Kind.RANGE, range.getKind());
        assertEquals("9007199254740993", range.getMin());
        assertEquals("9007199254740999", range.getMax());
        assertFalse(range.isMinInclusive());
        assertTrue(range.isMaxInclusive());

        var text = property(model, "SpecializedRangeAndText", "label").getConstraints();
        assertEquals(2, text.size());
        assertEquals(PropertyConstraint.Kind.LENGTH, text.get(0).getKind());
        assertEquals(1, text.get(0).getMinLength());
        assertEquals(4, text.get(0).getMaxLength());
        assertEquals(PropertyConstraint.Kind.PATTERN, text.get(1).getKind());
        assertTrue(text.get(1).isPortable());
        assertEquals("^(?:[A-Z]\\d?)$", text.get(1).getPattern());
        assertEquals("^(?:[A-Z]\\d?)(?![\\s\\S])", text.get(1).getSchemaPattern());

        var ts = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(ts.getMessage().contains(".label uses declared value constraints"));
    }

    @Test
    public void javaRenderingRetainsExpressionsAndDuplicateHandling() {
        Model legacy = Model.newInstance(RangeAndText.class, RepeatedLength.class);
        var repeated = legacy.resolveType(legacy.getPackageName() + ".RepeatedLength").orElseThrow();
        var declaration = repeated.resolveProp("label").orElseThrow();
        assertEquals(List.of(
                "ValueConstraint.StringLength(1, 4)",
                "ValueConstraint.StringPattern(\"^(?:[A-Z]\\\\d?)$\", \"^(?:[A-Z]\\\\d?)\\\\z\", "
                        + "\"^(?:[A-Z]\\\\d?)(?![\\\\s\\\\S])\")"), declaration.getConstraintExpressions());
        var range = repeated.getImplementation().getProperties().stream()
                .filter(prop -> prop.getName().equals("counter")).findFirst().orElseThrow();
        assertEquals(List.of("ValueConstraint.NumericRange(\"9007199254740993\", false, \"9007199254740999\", true)"),
                range.getConstraintExpressions());
    }

    @Test
    public void javaOnlyPatternRemainsAvailableToJavaAndIsRejectedByCSharp() {
        ResolvedModel model = model(JavaPattern.class);
        var pattern = property(model, "JavaPattern", "text").getConstraints().get(0);
        assertEquals("(?i)abc", pattern.getPattern());
        assertFalse(pattern.isPortable());
        assertNull(pattern.getSchemaPattern());
        var cs = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(model));
        assertTrue(cs.getMessage().contains(".text uses a Java-only @Pattern"));
    }

    @Test
    public void unsupportedOrAmbiguousPatternsStayOutOfPortableTargets() {
        var model = model(JavaPattern.class, ConflictingPattern.class);
        var constraint = property(model, "ConflictingPattern", "text").getConstraints().get(0);
        assertEquals(List.of("(?i)abc", "abc"), constraint.getPatternBindings("csharp"));
        var cs = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(model));
        assertTrue(cs.getMessage().contains("ConflictingPattern.text needs one CLR pattern binding"));
        var ts = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertTrue(ts.getMessage().contains("ConflictingPattern.text uses declared value constraints"));
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static Property property(ResolvedModel model, String type, String name) {
        return model.findType(type).orElseThrow().getEffectiveProperties().stream()
                .filter(property -> property.getName().equals(name)).findFirst().orElseThrow();
    }

    private static void assertScalarPair(ResolvedModel model, String typeSuffix, Kind kind) {
        String simple = Character.toLowerCase(typeSuffix.charAt(0)) + typeSuffix.substring(1);
        Property primitive = property(model, "PortableScalars", simple);
        Property boxed = property(model, "PortableScalars", "boxed" + typeSuffix);
        assertEquals(kind, primitive.getValueType().getKind());
        assertTrue(primitive.getValueType().isPrimitive());
        assertTrue(primitive.isRequired());
        assertEquals(kind, boxed.getValueType().getKind());
        assertFalse(boxed.getValueType().isPrimitive());
        assertFalse(boxed.isRequired());
    }
}
