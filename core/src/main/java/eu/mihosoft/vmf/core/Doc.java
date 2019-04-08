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
 * Adds user defined documentation to the generated API. It supports the annotation
 * of interfaces as well as methods.
 * 
 * <h3>Example Model:</h3>
 * <pre><code>
 * package mypkg.vmfmodel;
 * import eu.mihosoft.vmf.core.*;
 * 
 * @Doc("A finite state machine.")
 * interface FSM {
 * 
 *     @Doc("The current state of this FSM.")
 *     State getCurrentState();
 * 
 *     // ...
 * }
 * </code></pre>
 * 
 * The custom model documentation will be added in addition to the automatically
 * generated API documentation. This is a convenient way to define domain specific 
 * information.
 * 
 * <p>Created by miho on 08.04.2019.</p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-14/README.md">Tutorial on Custom Model Documentation</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Doc {
    String value() default "";
}