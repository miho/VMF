/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmf.runtime.core;

import java.util.List;

/**
 * Diffing feature for the object graph. Can be accessed via the {@code vmf()} method:
 *
 * <pre><code>
 * VObject o = ...
 * Diffing r = o.vmf().diffing()
 * </code></pre>
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 * @author Paul Zügel
 * @see {@link VMF}
 */

public interface Diffing {

    /**
     * Initializes diffing function.
     * @param targetObj the target object to which the source object is compared
     */
    void init(VObject targetObj);

    /**
     * Setting the source object
     * @param source the source object
     */
    void setSource(VObject source);

    /**
     * Prints out step by step changes onto the console
     */
    void printChanges();

    /**
     * Applies changes onto the source object
     * @return returns if changes successful
     */
    boolean applyChanges();

    /**
     * Returns a list of changes to transform the source object into the target object
     * @return list of changes
     */
    List<Change> getChanges();
}
