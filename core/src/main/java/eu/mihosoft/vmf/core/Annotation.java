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
 * An annotation can be used to add custom metadata to entities and properties of a model. 
 * 
 * <h3>Example Model:</h3>
 * <pre><code>
 * package mypkg.vmfmodel;
 * 
 * import eu.mihosoft.vmf.core.*;
 * 
 * interface Node {
 * 
 *     String getId();
 * 
 *     {@literal @}Annotation(key="api",value="input")
 *     Node getA();
 * 
 *     {@literal @}Annotation(key="api",value="input")
 *     Node getB();
 * 
 *     {@literal @}Annotation(key="api",value="output")
 *     Node getC();
 * 
 * }</code></pre>
 * 
 * <h3>Retrieving Annotations:</h3>
 * 
 * We can retrieve property annotations with the following API call:
 * 
 * <pre><code>
 * Property p = ...
 * List<Annotation> annotations = p.annotations();
 * </code></pre>
 * 
 * <h3>Filter Annotations:</h3>
 * 
 * Using the stream API, one can query a specific annotation (e.g. filtered by key). Here's an example that prints
 * all properties of a node instance annotated as input (<b>key="api"</b>, <b>value="input"</b>).
 * 
 * <pre><code>
 * // predicate to filter inputs
 * Predicate<Property> isInput = (p) -> {
 *     return p.annotationByKey("api").
 *         map(ann->"input".equals(ann.getValue())).orElse(false);
 * };
 * 
 * // query inputs:
 * n.vmf().reflect().properties().stream().filter(isInput).forEach(p->{
 *     System.out.println(
 *         "-> input  param '" + p.getName() + "' -> node: " + ((Node)p.get()).getId()
 *     );
 * });
 * </code></pre>   
 * 
 * <p>Created by miho on 05.12.2018.</p>
 * 
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-11/README.md">Tutorial on Annotation Support</a>
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(value = Annotations.class)
public @interface Annotation {
    /**
     * Key of the annotation, e.g., <b>"api"</b> or <b>"model"</b>.
     */
    String key() default "";

    /**
     * Value of the annotation. This can be an arbitrary string. Consumers of the annotation are responsible for
     * parsing the value string.
     */
    String value();
}

