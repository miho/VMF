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
package eu.mihosoft.vmftest.genericseries;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class GenericSeriesBuilderTest {
    @Test
    public void appendAndReplaceWorkAcrossInheritedAndConcreteInputs() {
        var source = TemperatureSeries.newBuilder().withSamples(2.0).build();
        SampleSeries<Double> inherited = source;
        var builder = TemperatureSeries.newBuilder().withSamples(1.0);
        builder.applyFrom(inherited).applyFrom(source);
        assertEquals(List.of(1.0, 2.0, 2.0), builder.build().getSamples());
        builder.appendCollections(false).applyFrom(source);
        assertEquals(List.of(2.0), builder.build().getSamples());
        TemperatureSeries.newBuilder().applyTo(source);
        assertEquals(List.of(2.0), source.getSamples());
        TemperatureSeries.newBuilder().appendCollections(false).applyTo(source);
        assertEquals(List.of(), source.getSamples());
    }
}
