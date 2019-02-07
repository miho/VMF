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
 * Indicates that only the getter method of a property should be publically available. This is no alternative to immutable
 * types. Actually, the purpose of this annotation is to allow a common interface for immutable and mutable types. It is
 * only valid in combination with {@link InterfaceOnly} and cannot be applied to regular types.
 * 
 * <h3>Example Model:</h3>
 * <pre><code>
 * package mypkg.vmfmodel;
 * import eu.mihosoft.vmf.core.*;
 * 
 * @InterfaceOnly
 * interface WithName {
 * 
 *   @GetterOnly
 *   String getName();
 * 
 * }
 * 
 * interface ImmutableObj extends WithName { }
 * 
 * interface MutableObj extends WithName { }
 * </code></pre>
 * 
 * Both, the mutable and the immutable entity can be casted to their common super interface {@code WithName}.
 * 
 * <pre><code>
 * ImmutableObj immutableObj = ImmutableObj.newBuilder().withName("immutable obj").build();
 * MutableObj   mutableObj   = MutableObj.newBuilder().withName("mutable obj").build();
 * WithName withName1 = immutableObj;
 * WithName withName2 = mutableObj;
 * </code></pre>
 * 
 * This is possible because VMF detects that {@code WithName} is effectively 'immutable', i.e., 
 * equivalent to its read-only type {@code ReadOnlyWithName} and only has/inherits immutable property types. 
 * That is, it is compatible with the immutable type which can safely inherit from the {@code WithName}
 * interface without breaking its contract.
 * 
 * <p>Created by miho on 02.01.2017.</p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 * 
 * @see {@link InterfaceOnly}
 * @see {@link Immutable}
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-07/README.md#how-can-mutable-and-immutable-types-share-a-common-super-type">Tutorial on Immutable Objects and ReadOnly API</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GetterOnly {

}
