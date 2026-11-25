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
package eu.mihosoft.vmftest.modelvalidation.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Length;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Pattern;
import eu.mihosoft.vmf.core.Range;

interface ValidationRoot {
    @Multiplicity(lower = 1)
    @Length(min = 3, max = 8)
    String getName();

    @Contains(opposite = "owner")
    Segment[] getSegments();

    Segment getRemoteSegment();
}

interface Window {
    @Range(min = "0", max = "10", maxInclusive = false)
    Double getStart();

    Double getEnd();
}

interface Segment extends Window {
    @Container(opposite = "segments")
    ValidationRoot getOwner();

    @Multiplicity(lower = 1)
    @Length(min = 2, max = 8)
    @Pattern("^[a-z]+$")
    String getCode();

    Segment getNext();
}
