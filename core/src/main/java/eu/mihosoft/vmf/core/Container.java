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

import java.lang.annotation.*;

/**
 * Used to define a containment relationship.
 * 
 * <h2>Example Model:</h2>
 * <pre><code>
 * package mypkg.vmfmodel;
 * 
 * import eu.mihosoft.vmf.core.*;
 *
 * interface Parent {
 * 
 *     {@literal @}Contains(opposite = "parent")
 *     Child getChild();
 * 
 *     String getName();
 * }
 * 
 * interface Child {
 *     {@literal @}Container(opposite="child")
 *     Parent getParent();
 * 
 *     int getValue();
 * }
 * </code></pre>
 * 
 * <p>Created by miho on 02.01.2017.</p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 * @see Contains
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-03/README.md">Tutorial on Containment References</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Container {
   /**
     * Sets the opposite property of this containment relationship, e.g., <b>"child"</b> (short form) or <b>"ClassName.child"</b> (full property).
     */
    String opposite() default "";
}
