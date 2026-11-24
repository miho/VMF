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
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Proceedings of the 2013 ACM workshop on Domain-Specific Modeling - DSM '13.
 * doi:10.1145/2541928.2541935
 */
package eu.mihosoft.vmf.jackson.test.collectionpersistence.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

@InterfaceOnly interface Identified { String getId(); }

@InterfaceOnly interface Index<K, V> { @Multiplicity(upper = 4) VMap<K, V> getRatings(); }

interface Catalog extends Identified, Index<Double, String> {
    @Contains @Keyed(by = "code") Item[] getItems();
    @Contains @Keyed(by = "target") Entry[] getEntries();
    @Multiplicity(upper = 3, kind = Multiplicity.Kind.ORDERED_SET) List<String> getPriority();
    Set<String> getTags();
    @Multiplicity(upper = 2) VMap<String, String> getNotes();
}

interface Item extends Identified { String getCode(); }

interface Entry extends Identified {
    Item getTarget();
    Set<Integer> getFlags();
    VMap<Integer, Boolean> getBits();
}

@Immutable interface Label { String getText(); }
@InterfaceOnly interface History<T> { T getCurrent(); T[] getPrevious(); }
interface LabelHistory extends History<Label> {}

interface ReferenceGroup extends Identified {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) Item[] getItems();
    Set<Item> getSelected();
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) Item[] getPriority();
}

interface ReferenceShelf extends Identified {
    @Contains Set<ReferenceGroup> getGroups();
}

interface LabelSelection {
    Set<Label> getSelected();
}

interface Network extends Identified {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) Vertex[] getVertices();
}

interface Vertex extends Identified {
    @eu.mihosoft.vmf.core.Refers(opposite = "previous") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) Vertex[] getNext();
    @eu.mihosoft.vmf.core.Refers(opposite = "next") Set<Vertex> getPrevious();
}
