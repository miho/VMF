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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reflection API of an object/type. The reflection API of an object can be accessed via the {@code vmf()} method:
 * 
 * <pre><code>
 * VObject o = ...
 * Reflect r = o.vmf().reflect()
 * </code></pre>
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 * @see {@link VMF}
 */
public interface Reflect {

    /**
     * Returns the list of annotations of this object.
     * @return the list of annotations of this object
     */
    List<Annotation> annotations();

    /**
     * Returns annotation specified by key.
     * @param key the key of the annotation to return
     * @return the first annotation with the specified key
     * @see #annotationsByKey(String)
     */
    default Optional<Annotation> annotationByKey(String key) {
        return annotations().stream().filter(a->key.equals(a.getKey())).findFirst();
    }

    /**
     * Returns annotations specified by key.
     * @param key the key of the annotation to return
     * @return annotations specified by key
     * @see #annotationByKey(String)
     */
    default List<Annotation> annotationsByKey(String key) {
        return annotations().stream().filter(a->key.equals(a.getKey())).collect(Collectors.toList());
    }

    /**
     * Returns the list of properties of this object.
     * @return the list of properties of this object
     */
    List<Property> properties();

    /**
     * Returns the model property specified by name.
     * @param name the model property specified by name
     * @return the model property specified by name
     */
    default Optional<Property> propertyByName(String name) {
        return properties().stream().filter(p->name.equals(p.getName())).findFirst();
    }
}
