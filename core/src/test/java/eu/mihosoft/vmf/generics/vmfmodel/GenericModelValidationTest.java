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
package eu.mihosoft.vmf.generics.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Model;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GenericModelValidationTest {
    @Test
    public void unsupportedDeclarationsReportTheirOwnerAndUseSite() {
        rejects(OpenConcrete.class, "@InterfaceOnly");
        rejects(Raw.class, "requires type arguments");
        rejects(Nested.class, "java.util.List<java.lang.String>");
        rejects(InvalidContainment.class, "getValue()");
        rejects(Wildcard.class, "getValues()");
        rejects(GenericGetter.class, "getValue()");
        rejects(ArrayWithErasedElement.class, "getValues()");
        rejects(NestedRawElement.class, "getValues()");
    }

    private void rejects(Class<?> model, String reason) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Model.newInstance(model));
        assertTrue(failure.getMessage(), failure.getMessage().contains(model.getSimpleName()));
        assertTrue(failure.getMessage(), failure.getMessage().contains(reason));
    }
}

interface OpenConcrete<T> { T getValue(); }
@InterfaceOnly interface Base<T> { T getValue(); }
@SuppressWarnings("rawtypes") interface Raw extends Base {}
interface Nested extends Base<List<String>> {}
@InterfaceOnly interface InvalidContainment<T> { @Contains T getValue(); }
interface Wildcard { List<?> getValues(); }
interface GenericGetter { <T> T getValue(); }
interface ArrayWithErasedElement { List<String>[] getValues(); }
@SuppressWarnings("rawtypes") interface NestedRawElement { List<List> getValues(); }
