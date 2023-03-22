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
package eu.mihosoft.vmf.runtime.core;

import java.util.Iterator;

/**
 * Traversal listener for traversing object graphs and performing corresponding actions.
 *
 * <p>Created by miho on 10.03.2017.</p>
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public interface TraversalListener {

    /**
     * Called whenever the traversal algorithm enters an object node.
     *
     * @param o object node
     */
    void onEnter(VObject o);

    /**
     * Called whenever the traversal algorithm exits an object node.
     *
     * @param o object node
     */
    void onExit(VObject o);

    /**
     * Indicates whether {@code null} objects are ignored.
     *
     * @return {@code true} if {@code null} objects are ignored; {@code false} otherwise
     */
    default boolean isIgnoreNullObjects() { return true; }

    /**
     * Traverses the specified object graph with the default strategy.
     *
     * @param o object graph to be traversed
     * @param tl traversal listener
     * @see VIterator.IterationStrategy
     */
    static void traverse(VObject o, TraversalListener tl) {
        traverse(o,tl, VIterator.IterationStrategy.UNIQUE_NODE);
    }

    /**
     * Traverses the specified object graph.
     *
     * @param o object graph to be traversed
     * @param tl traversal listener
     * @param strategy iteration strategy
     */
    static void traverse(VObject o, TraversalListener tl, VIterator.IterationStrategy strategy) {
        Iterator<VObject> it = VIterator.of(o, tl, strategy);

        while(it.hasNext()) {
            it.next();
        }
    }
}
