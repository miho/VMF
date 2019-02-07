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
package eu.mihosoft.vmf.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Delegates method calls to custom behavior implementations. Usually, VMF models only declare properties
 * and annotate them to define a working data model. But sometimes, it is desirable to introduce custom
 * behavior that cannot be declared with VMF. Consider the following model.
 * 
 * <h3>Example Model:</h3>
 * <pre><code>
 * package mypkg.vmfmodel;
 * import eu.mihosoft.vmf.core.*;
 * 
 * interface ObjectWithCustomBehavior {
 *     
 *     int getA();
 *     int getB();
 * 
 *     {@literal @}DelegateTo(className="mypkg.CustomBehavior")
 *     int computeSum();
 * }
 * </code></pre>
 * 
 * For the custom behavior one could imagine a method that computes the sum of properties <b>{@code a}</b>
 * and <b>{@code b}</b>. To achieve that, one needs to provide an implementation of the 
 * <b>{@code DelegatedBehavior<T extends VObject}</b> interface. A sample implementation is given below:
 * 
 * <pre><code>
 * package mypkg;
 * 
 * import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
 * 
 * public class CustomBehavior implements DelegatedBehavior<ObjectWithCustomBehavior> {
 * 
 *     private ObjectWithCustomBehavior caller;
 * 
 *     {@literal @}Override
 *     public void setCaller(ObjectWithCustomBehavior caller) {
 *         this.caller = caller;
 *     }
 * 
 *     // Delegated behavior. It is called whenever {@code caller.computeSum()} is called. 
 *     // This method computes and returns the sum of property 'a' and 'b'.
 *     // @return sum of 'a' and 'b'
 *     public int computeSum() {
 *         return caller.getA() + caller.getB();
 *     }
 * }
 * </code></pre>
 * 
 * <p>Created by miho on 21.03.2017.</p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-08/README.md">Tutorial on Custom Behavior & Delegation</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface DelegateTo {
    String className();
}