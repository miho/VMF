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
package eu.mihosoft.vmftest.genericseries.vmfmodel;

import eu.mihosoft.vmf.core.InterfaceOnly;
import java.util.List;

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
