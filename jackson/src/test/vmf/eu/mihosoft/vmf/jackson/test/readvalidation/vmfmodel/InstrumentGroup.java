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
package eu.mihosoft.vmf.jackson.test.readvalidation.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Range;

interface InstrumentGroup {
    @Contains(opposite = "group") Instrument[] getInstruments();
    Instrument getPrimary();
}

interface Instrument {
    @Container(opposite = "instruments")
    @Multiplicity(lower = 1)
    InstrumentGroup getGroup();

    @Range(min = "0", max = "10")
    double getSetpoint();

    @Annotation(key = "vmf:jackson:rename", value = "alarm-enabled")
    boolean getAlarmEnabled();

    @Range(min = "0", max = "10")
    @Annotation(key = "vmf:jackson:rename", value = "alarm-limit")
    Double getAlarmLimit();
}
