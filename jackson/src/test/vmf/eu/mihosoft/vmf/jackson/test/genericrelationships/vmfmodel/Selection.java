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
package eu.mihosoft.vmf.jackson.test.genericrelationships.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;
import java.util.Set;

@InterfaceOnly interface Identified { String getId(); }

@InterfaceOnly
interface Selection<T extends Identified> extends Identified {
    @Contains
    @Keyed(by = "id")
    @Annotation(key = "vmf:jackson:rename", value = "entries")
    T[] getOwned();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) T[] getQueue();
    Set<T> getFavorites();
    T getCurrent();
}

@InterfaceOnly interface Forwarded<E extends Identified> extends Selection<E> {}
interface StationGroup extends Forwarded<Station> {}
interface Station extends Identified { String getName(); }

@InterfaceOnly interface HasText { @GetterOnly String getText(); }
@Immutable interface Label extends HasText {}
@InterfaceOnly interface Review<T extends HasText> {
    Set<T> getLabels();
    T getSelected();
}
interface LabelReview extends Review<Label> {}
