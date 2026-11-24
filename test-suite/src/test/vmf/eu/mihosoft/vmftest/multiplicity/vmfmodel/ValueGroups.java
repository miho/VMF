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
package eu.mihosoft.vmftest.multiplicity.vmfmodel;

import eu.mihosoft.vmf.core.*;
import java.util.List;
import java.util.Set;

@VMFEquals interface ValueGroups {
    @Multiplicity(upper=3) Set<String> getTags();
    @Multiplicity(kind=Multiplicity.Kind.ORDERED_SET, upper=3) List<String> getStages();
    @Multiplicity(upper=2) List<Integer> getSamples();
    @Multiplicity(lower=1) String getName();
    @Multiplicity(upper=0) String getUnused();
}
@Immutable interface FixedGroups {
    Set<String> getTags();
    @Multiplicity(kind=Multiplicity.Kind.ORDERED_SET) List<String> getStages();
}
@InterfaceOnly interface GroupsOf<T> { Set<T> getValues(); }
interface NumericGroups extends GroupsOf<Double> { }


interface Group {
    @Contains(opposite="parent") List<Member> getChildren();
    @Refers(opposite="groups") List<Member> getMembers();
}
interface Member {
    @Container(opposite="children") Group getParent();
    @Refers(opposite="members") List<Group> getGroups();
}

@InterfaceOnly interface BoundedCollection<T> {
    @Multiplicity(kind=Multiplicity.Kind.ORDERED_SET, lower=1, upper=2) List<T> getNumbers();
}
@InterfaceOnly interface NumericCollection extends BoundedCollection<Double> {
    @Override List<Double> getNumbers();
}
interface RedeclaredCollection extends NumericCollection {}
@InterfaceOnly interface RequiredName {
    @Required String getName();
}
interface NamedGroup extends RequiredName { @Override String getName(); }

interface DefaultGroups {
    @DefaultValue("java.util.Set.of(\"x\")") Set<String> getTags();
    @DefaultValue("java.util.List.of(\"a\",\"b\")")
    @Multiplicity(kind=Multiplicity.Kind.ORDERED_SET) List<String> getStages();
}
