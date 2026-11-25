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
 * 181â€“192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.validation.vmfmodel;

import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Length;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Pattern;
import eu.mihosoft.vmf.core.Range;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConstraintDeclarationTest {
    @Test
    public void malformedConstraintsIdentifyTheDeclaringProperty() {
        rejects(NonNumeric.class, "@Range requires a numeric scalar");
        rejects(NonString.class, "@Length requires a String scalar");
        rejects(CollectionLength.class, "@Length requires a String scalar");
        rejects(InvalidLength.class, "@Length has invalid bounds");
        rejects(InvalidBound.class, "@Range bounds must be finite decimal numbers");
        rejects(EmptyRange.class, "@Range has an empty interval");
        rejects(InvalidPattern.class, "@Pattern is invalid");
        rejects(JavaOnlyPattern.class, "@Pattern is outside the portable subset");
        rejects(QuantifiedAnchor.class, "@Pattern is outside the portable subset");

        Model.newInstance(ExplicitJavaPattern.class, PortablePattern.class);
    }

    @Test
    public void validatesGenericConstraintsAfterSpecialization() {
        Model.newInstance(GenericRange.class, NumericRange.class);
        var error = assertThrows(IllegalArgumentException.class, () ->
                Model.newInstance(GenericRange.class, StringRange.class));
        assertTrue(error.getMessage(), error.getMessage().contains("eu.mihosoft.vmf.validation.StringRange"));
        assertTrue(error.getMessage(), error.getMessage().contains("@Range requires a numeric scalar"));
    }

    private void rejects(Class<?> model, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class, () -> Model.newInstance(model));
        assertTrue(error.getMessage(), error.getMessage().contains(model.getName()));
        assertTrue(error.getMessage(), error.getMessage().contains("getValue()"));
        assertTrue(error.getMessage(), error.getMessage().contains(diagnostic));
    }
}

interface NonNumeric { @Range(min = "0") String getValue(); }
interface NonString { @Length(max = 2) int getValue(); }
interface CollectionLength { @Length(max = 2) List<String> getValue(); }
interface InvalidLength { @Length(min = 4, max = 2) String getValue(); }
interface InvalidBound { @Range(min = "NaN") double getValue(); }
interface EmptyRange { @Range(min = "1.00", max = "1", minInclusive = false) double getValue(); }
interface InvalidPattern { @Pattern("[") String getValue(); }
interface JavaOnlyPattern { @Pattern("\\p{Lu}") String getValue(); }
interface ExplicitJavaPattern { @Pattern(value = "\\p{Lu}", portable = false) String getValue(); }
interface PortablePattern { @Pattern("^(?:[A-Z]-)?\\d{2,4}$") String getValue(); }

interface QuantifiedAnchor { @Pattern("^*") String getValue(); }
@InterfaceOnly interface GenericRange<T> { @Range(min = "0") T getValue(); }
interface NumericRange extends GenericRange<Double> {}
interface StringRange extends GenericRange<String> {}
