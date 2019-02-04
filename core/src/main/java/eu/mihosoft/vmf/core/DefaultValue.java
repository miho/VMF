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
 * This model annotation allows to define custom default values for properties. 
 * 
 * <h3>Example Model:</h3>
 * 
 * <pre><code>
 * package mypkg.vmfmodel;
 *
 * import eu.mihosoft.vmf.core.*;
 * 
 * interface ObjectWithDefaultValues {
 *     
 *     {@literal @}DefaultValue(value="23")
 *     Integer getValue();
 * 
 *     {@literal @}DefaultValue(value="\"my name\"")
 *     String getName();
 * }
 * </code></pre>
 * 
 * <h3>Additional Notes:</h3>
 * Default values are also responsible for defining whether a value is set or unset. Usually, properties have
 * the default value <b>{@code null}</b>. It is set if it is not {@code null} and unset if it is {@code null}. 
 * If the default value is different from {@code null} it is unset if it is equal to the custom default value
 * and set if it is not equal to the custom default value.
 * 
 * <p>Created by miho on 18.06.2018.</p>
 * 
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-09/README.md">Custom Default Values for Properties</a>
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface DefaultValue {
    String value();
}