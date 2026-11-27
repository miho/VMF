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
 * 181â€“192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.genericseries.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;
import java.util.List;
import java.util.Set;

@InterfaceOnly
interface SampleSeries<T> {
    T getLatest();
    List<T> getSamples();
}

@InterfaceOnly
interface NumericSeries<N extends Number> extends SampleSeries<N> {}

interface TemperatureSeries extends NumericSeries<Double> {}
interface StatusSeries extends SampleSeries<String> {}
interface StateSeries extends SampleSeries<Thread.State> {}

@ExternalType(pkgName = "java.lang.Thread")
@Annotation(key = "vmf:csharp:external-value-type", value = "Eu.Mihosoft.Vmf.Conformance.Externalbinding.Host.ThreadPhase")
interface State {}

interface PrimitiveArrays {
    int[] getInts();
    long[] getLongs();
}

@InterfaceOnly
interface Lookup<V> {
    eu.mihosoft.vcollections.VMap<String, V> getValues();
}

interface StatusLookup extends Lookup<String> {}
interface NumericLookup extends Lookup<Double> {}

@InterfaceOnly
interface Pair<L, R> {
    L getLeft();
    R getRight();
}

@InterfaceOnly
interface Reversed<L, R> extends Pair<R, L> {}

interface NamedPair extends Reversed<Integer, String> {}

@InterfaceOnly
interface NamedTarget { String getName(); }

@InterfaceOnly interface Active { boolean isEnabled(); }
@InterfaceOnly interface ActiveTarget extends NamedTarget, Active {}
interface Station extends ActiveTarget {}

@InterfaceOnly
interface Selection<T extends NamedTarget> {
    @Contains
    @Keyed(by = "name")
    List<T> getOwned();
    T getCurrent();
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    T[] getQueue();
    Set<T> getFavorites();
}

@InterfaceOnly interface BranchA<X extends NamedTarget> extends Selection<X> {}
@InterfaceOnly interface BranchB<Y extends NamedTarget> extends Selection<Y> {}
interface StationSelection extends BranchA<Station>, BranchB<Station> {}

@InterfaceOnly
interface MemberLike {
    @Refers(opposite = "members")
    Team getTeam();
}
interface Member extends MemberLike {}

@InterfaceOnly
interface TeamOf<M extends MemberLike> {
    @Refers(opposite = "team")
    Set<M> getMembers();
}
interface Team extends TeamOf<Member> {}

@InterfaceOnly interface Related<B extends NamedTarget, S extends B> {
    B getBase();
    S getSpecific();
}
interface StationPair extends Related<NamedTarget, Station> {}

@InterfaceOnly interface Filtered<T extends ActiveTarget> { T getTarget(); }
interface FilteredStations extends Filtered<Station> {}

@InterfaceOnly interface Label { @GetterOnly String getText(); }
@Immutable interface ConstantLabel extends Label {}
@InterfaceOnly interface LabelsOf<E extends Label> { Set<E> getLabels(); }
interface Constants extends LabelsOf<ConstantLabel> {}

@InterfaceOnly interface LocalSelection extends BranchA<Station> {}

@InterfaceOnly interface ConstantsOf<T extends ConstantLabel> { Set<T> getLabels(); }
interface SpecificConstants extends ConstantsOf<ConstantLabel> {}

@InterfaceOnly interface OwnerOf<T extends OwnedTarget> {
    @Contains(opposite = "owner") T getPrimary();
    @Refers(opposite = "selector") T getHighlighted();
}
interface Owner extends OwnerOf<Owned> {
    @Override Owned getPrimary();
    @Override Owned getHighlighted();
}
@InterfaceOnly interface OwnedTarget {
    @Container(opposite = "primary") Owner getOwner();
    @Refers(opposite = "highlighted") Owner getSelector();
}
interface Owned extends OwnedTarget {}
