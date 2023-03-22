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

import java.lang.annotation.*;

/**
 * Used to define a cross reference (bidirectional linking relationship). 
 * <h3>Example Model:</h3>
 * <pre><code>
 * package mypkg.vmfmodel;
 * 
 * import eu.mihosoft.vmf.core.*;
 *
 * interface Book {
 *    String getTitle();
 *
 *    @Refers(opposite="books")
 *    Writer[] getAuthors();
 * }
 *
 * interface Writer {
 *    String getName();
 *
 *    @Refers(opposite="authors")
 *    Book[] getBooks();
 * }
 * </code></pre>
 * 
 * <p>Created by miho on 12.03.2019.</p>
 * 
 * @see Contains
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-03b/README.md">Tutorial on Cross References</a>
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Refers {
   /**
     * Sets the opposite property of this cross reference relationship, e.g., <b>"prop"</b> (short form) or <b>"ClassName.prop"</b> (full property).
     */
    String opposite();
}