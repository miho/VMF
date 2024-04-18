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

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.ElementType;

/**
 * Indicates that an automatically generated
 * {@code java.lang.Object#equals(Object)} and
 * {@code java.lang.Object#hashCode()} should be used instead of the default
 * implementation. If the annotation is not used, VMFs {@code equals()} and 
 * {@code hashCode()} methods can be accessed via {@code obj.content().equals()}
 * and  {@code obj.content().hashCode()}.
 * 
 * <p>
 * Created by miho on 14.10.19.
 * </p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 * 
 * @see {@link IgnoreEquals}
 * @see <a
 *      href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-10/README.md#ignoring-properties-for-equals">Tutorial
 *      on Equals &amp; HashCode</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface VMFEquals {

    /**
     * Defines the {@link Object#equals(Object)} and {@link Object#hashCode()}
     * implementation to use.
     */
    enum EqualsType {
        CONTAINMENT_AND_EXTERNAL,
        ALL,
        INSTANCE
    }

    /**
     * Defines the {@link Object#equals(Object)} and {@link Object#hashCode()}
     * implementation to use. The default is {@link EqualsType#INSTANCE}.
     */
    EqualsType value() default EqualsType.CONTAINMENT_AND_EXTERNAL;
}