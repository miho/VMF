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
package eu.mihosoft.vmf.runtime.core;

import eu.mihosoft.vcollections.VList;

import java.util.List;
import java.util.stream.Stream;

/**
 * Content of this object graph. The content API of an object can be accessed via the {@code vmf()} method:
 * 
 * <pre><code>
 * VObject o = ...
 * Content r = o.vmf().content()
 * </code></pre>
 * 
 * @author Michael Hoffer (info@michaelhoffer.de)
 * @see {@link VMF}
 */
public interface Content {

    /**
     * Checks declared multiplicity and value constraints on this object and its contained descendants.
     * Call this when a graph is ready to use or publish. Use a configured {@link ModelValidator}
     * to include application rules or follow non-containment references.
     * @return the validation report in containment and property order
     */
    default ValidationReport validate() {
        // Older generated Content implementations expose their owner as the first stream element.
        try (Stream<VObject> objects = stream(VIterator.IterationStrategy.CONTAINMENT_TREE)) {
            return objects.findFirst().map(ModelValidator.DEFAULT::validate)
                    .orElseGet(() -> new ValidationReport(List.of()));
        }
    }

    /**
     * Returns an iterator that traverses the object graph (depth first)
     * using the {@link VIterator.IterationStrategy#UNIQUE_NODE} iteration strategy.
     * @return an iterator that traverses the object graph
     */
    VIterator iterator();

    /**
     * Returns an iterator that traverses the object graph (depth first)
     * using the specified iteration strategy.
     * @param strategy iteration strategy
     * @return an iterator that traverses the object graph
     */
    VIterator iterator(VIterator.IterationStrategy strategy);

    /**
     * Returns a stream that contains all elements of the object graph (depth first)
     * using the {@link VIterator.IterationStrategy#UNIQUE_NODE} iteration strategy.
     * @return a stream that contains all elements of the object graph
     */
    Stream<VObject> stream();

    /**
     * Returns a stream that contains all elements of the object graph (depth first)
     * using the specified iteration strategy.
     * @param strategy iteration strategy
     * @return a stream that contains all elements of the object graph
     */
    Stream<VObject> stream(VIterator.IterationStrategy strategy);

    /**
     * Returns a stream that contains all elements of the object graph (depth first) that implement/extend the
     * specified type. It maps all elements to the specified type, i.e. returns {@code Stream<T>}.
     * @param type type for filtering and mapping
     * @param <T> element type
     * @return a stream of type <T>, i.e. {@code Stream<T>}
     */
    <T extends VObject> Stream<T> stream(Class<T> type);

    /**
     * Returns a stream that contains all elements of the object graph (depth first) that implement/extend the
     * specified type. It maps all elements to the specified type, i.e. returns {@code Stream<T>} using the specified
     * iteration strategy.
     * @param type type for filtering and mapping
     * @param strategy iteration strategy
     * @param <T> element type
     * @return a stream of type <T>, i.e. {@code Stream<T>}
     */
    <T extends VObject> Stream<T> stream(Class<T> type, VIterator.IterationStrategy strategy);

    /**
     * Returns an unmodifiable list of all objects that reference this object.
     *
     * @return an unmodifiable list of all objects that reference this object
     */
    @Deprecated // TODO 17.02.2019 feature deactivated due to huge memory consumption
    VList<VObject> referencedBy();

    /**
     * Returns an unmodifiable list of all objects that are referenced by this object.
     *
     * @return an unmodifiable list of all objects that are referenced by this object.
     */
    @Deprecated // TODO 17.02.2019 feature deactivated due to huge memory consumption
    VList<VObject> references();
    
    /**
     * Copies the VMF object graph using {@link VObject#clone()}. External property
     * values and external list elements are shared by reference.
     * @return a copy of the VMF object graph
     */
    <T> T deepCopy();
    
    /**
     * Returns a shallow copy of this object.
     * @return a shallow copy of this object
     */
    <T> T shallowCopy();

    /**
     * Indicates whether this VMF object is equal to the specified model object.
     * It uses VMFs equals implementation which compares all properties unless
     * they are ignored explicitly via annotation.
     *
     * <p>Example:</p>
     * <pre><code>
     *     MyModel m1 = ...
     *     MyModel m2 = ...
     *     boolean equals = m1.vmf().content().equals(m2);
     *     System.out.println("Models are equal: " + equals);
     *     // output: Models are equal: true
     * </code></pre>
     *
     * @param o object to compare
     * @return {@code } if equals; {@code false} otherwise
     */
    @Override
    boolean equals(Object o);
    /**
     * Returns the hash-code for this VMF object. It uses VMFs hashCode
     * implementation which takes all properties into account unless
     * they are ignored explicitly via annotation.
     * @return {@code } if equals; {@code false} otherwise
     */
    @Override
    int hashCode();

    
    
}

