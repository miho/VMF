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
 * Delegation base class for invoking custom implementations. VMF objects do not support
 * custom extensions by manipulating their implementation code. However, custom behavior can be realized be defining
 * delegation classes that extend this class.
 *
 * <p>Created by miho on 04.05.2020.</p>
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-08/README.md">Tutorial on Custom Behavior &amp; Delegation</a>
 * @see DelegatedBehavior
 */
public class DelegatedBehaviorBase<T extends VObject> implements DelegatedBehavior<T> {
    private T caller;

    /**
     * Returns the caller currently associated with this behavior.
     * @return the caller currently associated with this behavior or {@code null} if no such caller exists
     */
    public T getCaller() {
        return this.caller;
    }

    @Override
    public void setCaller(T caller) {
        this.caller = caller;
    }
}
