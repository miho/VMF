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
package eu.mihosoft.vmf.runtime.core;

import java.util.Objects;

/**
 * An annotation can contain compile-time meta-information that can be queried via VMFs reflection API ({@link Reflect}).
 * Model entities as well as properties can be annotated. 
 * 
 * @see {@link Reflect}
 * @see <a href=https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-11/README.md>Tutorial on Annitations</a>
 */
public interface Annotation {
    /**
     * Returns the annotation key which can be an arbitrary string. Keys are used to group annotations into categories.
     * @return annotation key, e.g., <b>{@code "api"}</b> or <b>{@code "model"}</b>
     */
    String getKey();
    /**
     * Returns the annotation value which can be an arbitrary string. Consumers of the annotation are responsible for
     * parsing the value string.
     * @return annotation value, e.g., <b>{@code "min=3;max=27"}</b>
     */
    String getValue();

    /**
     * Determines whether this annotation object is equal to the specified key and value.
     * @return {@code true} if this object is equal to the specified key and value; {@code false} otherwise
     */
    default boolean equals(String key, String value) {
        return Objects.equals(getKey(), key) && Objects.equals(value, getValue());
    }
}
