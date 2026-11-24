/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
package eu.mihosoft.vmftest.managedmap.invalid.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class InvalidMapDeclarationsTest {
    @Test
    public void genericMapReportsThePropertyAndItsInvalidBinding() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                VMF.generate(new MemoryResourceSet(), GenericLookup.class, InvalidLookup.class));
        assertTrue(ex.getMessage(), ex.getMessage().contains("GenericLookup.getEntries"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("InvalidLookup"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("java.math.BigDecimal"));
    }

    @Test
    public void diagnosticsIdentifyTheInvalidProperty() {
        for (Class<?> declaration : List.of(Raw.class, WrongKey.class, Nested.class, Contained.class)) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> VMF.generate(new MemoryResourceSet(), declaration));
            assertTrue(ex.getMessage(), ex.getMessage().contains(declaration.getSimpleName() + ".getEntries"));
        }
    }
}

@InterfaceOnly interface GenericLookup<V> { VMap<String, V> getEntries(); }
interface InvalidLookup extends GenericLookup<java.math.BigDecimal> {}

interface Raw { VMap getEntries(); }
interface WrongKey { VMap<java.util.Date, String> getEntries(); }
interface Nested { VMap<String, List<String>> getEntries(); }
interface Contained { @Contains VMap<String, String> getEntries(); }
