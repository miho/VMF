/*
 * Copyright 2017-2023 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2023 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
 * Entities annotated with this annotation are declared as being immutable, i.e., the state can only be set
 * during initialization via the builder pattern. The state is constant during the whole lifetime of an
 * immutable object. Consequently, it only provides getter methods for accessing its properties and only 
 * allows immutable property objects/types.
 * 
 * <h3>Example Model:</h3>
 * 
 * <pre><code>
 * package mypkg.vmfmodel;
 * 
 * import eu.mihosoft.vmf.core.*;
 * 
 * {@literal @}Immutable
 * interface ImmutableObject {
 *     int getValue();
 * }
 * </code></pre>
 * 
 * The state of <b>{@code ImmutableObject}</b> instances can be defined via its associated builder:
 * 
 * <pre><code>
 * ImmutableObject obj = ImmutableObject.newBuilder().withValue(123).build();
 * </code></pre>
 * 
 * The <b>{@code value}</b> property of {@code obj} cannot be changed anymore.
 * 
 * As an example, the following code will not compile:
 * 
 * <pre><code>
 * ImmutableObject obj = ImmutableObject.newInstance();
 * obj.setValue(); // there is no setter method
 * </code></pre>
 * 
 * <h3>Additional Notes:</h3>
 * 
 * VMF uses shared immutable instances for cloning. That is, while state changes of immutable 
 * properties require new instantiations (which might be performance critical) they can improve
 * the performance and memory footprint of cloning since immutable references can be shared.
 * 
 * <p>Created by miho on 10.03.17.</p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-07/README.md">Tutorial on Immutable Objects and ReadOnly API</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Immutable {
}
