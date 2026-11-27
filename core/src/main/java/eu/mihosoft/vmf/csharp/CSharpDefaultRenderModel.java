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
 * Computing and Visualization in Science, 2013, 16(4), 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ScalarDefault;

import java.util.Locale;

/** Formats a portable default from its already resolved scalar value. */
final class CSharpDefaultRenderModel {
    private CSharpDefaultRenderModel() {
    }

    static String fieldInitializer(Property property) {
        return property.getScalarDefault().map(value -> " = " + literal(value)).orElse("");
    }

    static boolean writesExplicitNull(Property property) {
        return !property.getValueType().isPrimitive()
                && (CSharpCapabilities.bindsExecutableDefault(property)
                    && !"java.util.Map".equals(property.getValueType().getExternalType())
                || property.getScalarDefault().filter(value -> !value.isNull()).isPresent());
    }

    static String literal(ScalarDefault scalar) {
        Object value = scalar.getValue();
        if (value == null) return "null";
        switch (scalar.getKind()) {
            case BOOLEAN:
            case INT8:
            case INT16:
            case INT32:
                return value.toString();
            case INT64:
                return (long) value == Long.MIN_VALUE ? "long.MinValue" : value + "L";
            case CHAR:
                return String.format(Locale.ROOT, "'\\u%04X'", (int) (char) value);
            case FLOAT32:
                return String.format(Locale.ROOT,
                        "global::System.BitConverter.Int32BitsToSingle(unchecked((int)0x%08XU))",
                        Float.floatToRawIntBits((float) value));
            case FLOAT64:
                return String.format(Locale.ROOT,
                        "global::System.BitConverter.Int64BitsToDouble(unchecked((long)0x%016XUL))",
                        Double.doubleToRawLongBits((double) value));
            case STRING:
                return CSharpSnapshotRenderModel.literal((String) value);
            default:
                throw new IllegalStateException("Unsupported C# default kind " + scalar.getKind());
        }
    }
}
