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

/**
 * Delegation interface for invoking custom implementations. VMF objects do not support
 * custom extensions by manipulating their implementation code. However, custom behavior can be realized be defining
 * delegation classes that implement this interface.
 *
 * <p>Created by miho on 05.04.17.</p>
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-08/README.md">Tutorial on Custom Behavior & Delegation</a>
 */
public interface DelegatedBehavior<T extends VObject> {

    /**
     * Sets the caller that delegates to this class. This method is called by VMF directly
     * after initializing this object.
     * @param caller the caller that delegates to this class
     */
    public default void setCaller(T caller) {}
}
