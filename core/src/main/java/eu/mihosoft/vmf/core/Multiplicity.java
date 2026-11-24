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

/** Declares cardinality and collection semantics for a model property.
 * A negative upper bound of -1 denotes unlimited cardinality for a many-valued property.
 * Set declarations are intrinsically unordered and unique. List declarations are ordered.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Multiplicity {
    /** Minimum number of values; checked when validating a complete model. */
    int lower() default 0;
    /** Inclusive maximum. The default is unlimited for collections and one for scalars. */
    int upper() default -1;
    /** Selects a many-valued collection shape. By default the declaration's type determines the shape. */
    Kind kind() default Kind.INFERRED;

    /** Sequence and ordered set retain positions. Set and ordered set enforce uniqueness. */
    enum Kind {
        INFERRED, SEQUENCE, ORDERED_SET, SET, BAG
    }
}
