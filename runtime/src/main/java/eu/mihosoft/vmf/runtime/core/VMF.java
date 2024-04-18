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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.mihosoft.vmf.runtime.core;

import eu.mihosoft.vmf.runtime.core.internal.ChangesImpl;

/**
 * VMF API. Gives access to VMF related functionality.
 * 
 * @author Michael Hoffer (info@michaelhoffer.de)
 * @see {@link Content}
 * @see {@link Change}
 * @see {@link Reflect}
 */
public interface VMF {
    
    /**
     * Returns the content of this object.
     * @return the content of this object
     */
    default Content content() {
        throw new UnsupportedOperationException("FIXME: unsupported method invoked. This should not happen :(");
    }
    
    /**
     * Returns the changes applied to this object.
     * @return the changes applied to this object
     */
    default Changes changes() {
        throw new UnsupportedOperationException("FIXME: unsupported method invoked. This should not happen :(");
    }

    /**
     * Returns the reflection API of this object.
     * @return the reflection API of this object
     */
    default Reflect reflect() {
        throw new UnsupportedOperationException("FIXME: unsupported method invoked. This should not happen :(");
    }

    /**
     * Returns the behavior API of this object.
     * @return the behavior API of this object
     */
    default <T extends VObject> Behavior<T> behavior() {
        throw new UnsupportedOperationException("FIXME: unsupported method invoked. This should not happen :(");
    }
    
}
