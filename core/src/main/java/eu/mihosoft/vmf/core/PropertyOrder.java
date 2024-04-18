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
package eu.mihosoft.vmf.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation can be used to define a property order. The order is used for object graph traversal and
 * reflection.
 * 
 * <h3>Example Model:</h3>
 * <pre><code>
 * package mypkg.vmfmodel;
 *
 * interface Node {
 * 
 *     {@literal @}PropertyOrder(index = 0)
 *     String getName();
 * 
 *     {@literal @}PropertyOrder(index = 2)
 *     Boolean getVisible();
 * 
 *     {@literal @}PropertyOrder(index = 1)
 *     Node getChild();
 * }
 * </code></pre>
 * 
 * Use <b>{@code node.vmf().reflect().properties()}</b> to access the complete list of properties of a node object in the
 * specified order (<b>"name"</b>, <b>"child"</b>, <b>"visible"</b>).
 * 
 * <p>Created by miho on 02.01.2017.</p>
 * 
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-06/README.md">Tutorial on custom Property Order</a>
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PropertyOrder {
    int index();
}
