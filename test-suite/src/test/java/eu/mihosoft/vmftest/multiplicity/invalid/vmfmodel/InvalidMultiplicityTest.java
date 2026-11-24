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
package eu.mihosoft.vmftest.multiplicity.invalid.vmfmodel;

import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.*;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class InvalidMultiplicityTest {
    @Test public void rejectsIncompatibleInheritedContractsByOwnerAndProperty() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> VMF.generate(new MemoryResourceSet(), A.class, B.class, ConflictingDiamond.class));
        assertTrue(error.getMessage(), error.getMessage().contains("ConflictingDiamond.getLabels"));
        assertTrue(error.getMessage(), error.getMessage().contains("A.getLabels"));
        assertTrue(error.getMessage(), error.getMessage().contains("B.getLabels"));
    }

    @Test public void reportsThePropertyAndUnsupportedContract() {
        for (Class<?> invalid : List.of(Raw.class, Wildcard.class, Bag.class, SetOfLists.class,
                OrderedSet.class, NegativeBound.class, InvertedBounds.class, BadScalar.class, ModelSet.class,
                UniqueRelation.class, SetSequence.class, ScalarKind.class, MapKind.class, WrongListKind.class, OptionalPrimitive.class)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> VMF.generate(new MemoryResourceSet(), invalid, Element.class));
            assertTrue(error.getMessage(), error.getMessage().contains(invalid.getSimpleName() + ".getEntries"));
        }
    }
}

interface Raw { Set getEntries(); }
interface Wildcard { Set<?> getEntries(); }
interface SetOfLists { Set<List<String>> getEntries(); }
interface Bag { @Multiplicity(kind=Multiplicity.Kind.BAG) List<String> getEntries(); }
interface OrderedSet { @Multiplicity(kind=Multiplicity.Kind.ORDERED_SET) Set<String> getEntries(); }
interface InvertedBounds { @Multiplicity(lower=3,upper=2) Set<String> getEntries(); }
interface NegativeBound { @Multiplicity(lower=-1) int getEntries(); }
interface BadScalar { @Multiplicity(upper=3) String getEntries(); }
interface ModelSet { Set<Element> getEntries(); }
interface UniqueRelation { @Contains @Multiplicity(kind=Multiplicity.Kind.ORDERED_SET) List<Element> getEntries(); }
interface Element { String getName(); }

interface SetSequence { @Multiplicity(kind=Multiplicity.Kind.SEQUENCE) Set<String> getEntries(); }
interface ScalarKind { @Multiplicity(kind=Multiplicity.Kind.SET) String getEntries(); }
interface MapKind { @Multiplicity(kind=Multiplicity.Kind.SET) eu.mihosoft.vcollections.VMap<String,String> getEntries(); }
interface WrongListKind { @Multiplicity(kind=Multiplicity.Kind.SET) List<String> getEntries(); }
@InterfaceOnly interface A { @Multiplicity(upper=2) List<String> getLabels(); }
@InterfaceOnly interface B { @Multiplicity(kind=Multiplicity.Kind.ORDERED_SET,upper=2) List<String> getLabels(); }
interface ConflictingDiamond extends A,B {}
interface OptionalPrimitive { @Multiplicity(lower=0) boolean getEntries(); }
