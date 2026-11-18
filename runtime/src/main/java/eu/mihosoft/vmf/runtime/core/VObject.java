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
package eu.mihosoft.vmf.runtime.core;

/**
 * VMF base class. All generated classes of a VMF model implement this interface.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public interface VObject {
    //static T newInstance();

    /**
     * Returns the VMF related functionality.
     * 
     * @return VMF related functionality
     */
    VMF vmf();
    
    /**
     * Copies the VMF object graph. External property values and external list elements
     * are shared by reference. Store immutable values or copy mutable payloads separately
     * when the result needs to be an isolated snapshot.
     * @return a copy of the VMF object graph
     */
    VObject clone();
    
    /**
     * Returns a live read-only wrapper of this object. External property values
     * and external list elements keep their original mutability.
     * @return a read-only wrapper of this object
     */
    VObject asReadOnly();
}
