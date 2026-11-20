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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.mihosoft.vmf.runtime.core;

import eu.mihosoft.vcollections.VList;

import vjavax.observer.Subscription;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Contains change related functionality of this object graph. The change API of an object can be
 * accessed via the {@code vmf()} method:
 * 
 * <pre><code>
 * VObject o = ...
 * Changes changes = o.vmf().changes();
 * </code></pre>
 *
 * <p>Live listeners run synchronously. A recursive subscription follows containment.
 * Changes to a reference property are visible on its owner. Pointing a reference
 * at an object does not subscribe the listener to that object's properties.</p>
 *
 * <p>In generated VMF models, if a live listener throws, delivery continues to the
 * other listeners in the dispatch snapshot. Applied edits remain on the model.
 * A containment mutation completes its parent and child updates before returning
 * the first unchecked listener failure to the caller. Further failures may be
 * inspected with {@link Throwable#getSuppressed()}.</p>
 * 
 * @author Michael Hoffer (info@michaelhoffer.de)
 * @see {@link VMF}
 */
public interface Changes {

    /** The objects to observe when subscribing to a filtered change stream. */
    enum Scope {
        /** Observe only the object whose change stream this is. */
        LOCAL,
        /** Observe the object and its descendants using recursive observation. */
        RECURSIVE
    }

    /**
     * Adds the specified change listener. Listeners will be notified about changes regardless
     * of whether change recording is enabled. This allows to react to and/or undo specific
     * changes without the overhead of storing all previous events in a collection. The listener registers
     * with all objects of the current object graph.
     *
     * @param l the listener to add
     * @return a subscription which allows to unsubscribe the specified listener
     */
    Subscription addListener(ChangeListener l);

    /**
     * Adds the specified change listener. Listeners will be notified about changes regardless
     * of whether change recording is enabled. This allows to react to and/or undo specific
     * changes without the overhead of storing all previous events in a collection. Optionally the
     * listener registers with all objects of the current object graph.
     *
     * @param l the listener to add
     * @param recursive determines whether to add the listener recursively to all objects of the
     *                  current object graph
     * @return a subscription which allows to unsubscribe the specified listener
     */
    Subscription addListener(ChangeListener l, boolean recursive);

    /**
     * Observes changes to this object, including edits to its list properties.
     *
     * @param listener the listener to add
     * @return a subscription that removes the listener
     */
    default Subscription addLocalListener(ChangeListener listener) {
        return addListener(listener, false);
    }

    /**
     * Observes changes to this object and to objects it contains, including
     * descendants added while the listener is subscribed.
     *
     * @param listener the listener to add
     * @return a subscription that removes the listener
     */
    default Subscription addRecursiveListener(ChangeListener listener) {
        return addListener(listener, true);
    }

    /**
     * Observes changes to the named property of this object. List edits also
     * match the name of their list property.
     *
     * @param propertyName the exact name to match
     * @param listener the listener to add
     * @return a subscription that removes the listener
     */
    default Subscription addPropertyListener(String propertyName, ChangeListener listener) {
        return addPropertyListener(propertyName, listener, Scope.LOCAL);
    }

    /**
     * Observes changes to properties with the given name in the selected
     * scope. With recursive observation, the name is matched on each source
     * object, including when the property is absent on the root.
     *
     * @param propertyName the exact name to match
     * @param listener the listener to add
     * @param scope the objects to observe
     * @return a subscription that removes the listener
     */
    default Subscription addPropertyListener(String propertyName, ChangeListener listener, Scope scope) {
        Objects.requireNonNull(propertyName, "propertyName");
        return addFilteredListener(change -> propertyName.equals(change.propertyName()), listener, scope);
    }

    /**
     * Observes changes accepted by a predicate. The predicate runs during
     * normal change delivery, at the point where this listener would have
     * run. Filtering only affects delivery to this listener.
     *
     * @param filter the predicate to evaluate for each change in scope
     * @param listener the listener to add
     * @param scope the objects to observe
     * @return a subscription that removes the listener and filter
     */
    default Subscription addFilteredListener(Predicate<? super Change> filter, ChangeListener listener,
                                             Scope scope) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(scope, "scope");
        return addListener(change -> {
            if (filter.test(change)) {
                listener.onChange(change);
            }
        }, scope == Scope.RECURSIVE);
    }

    /**
     * Starts recording changes. Previously recorded changes will be removed
     * (also removes transactions).
     * @see Changes#stop()
     */
    void start();

    /**
     * Starts a new transaction.
     * @see Transaction
     * @see Changes#publishTransaction()
     */
    void startTransaction();

    /**
     * Publishes a transaction that consists of all changes since the last
     * {@code startTransaction()} or {@code publishTransaction()} call.
     * @see Transaction
     * @see Changes#startTransaction()
     */
    void publishTransaction();

    /**
     * Stops recording changes. Unpublished transactions will be published.
     * @see Changes#start()
     */
    void stop();

    /**
     * Returns a live, read-only, observable view of the changes recorded since the last
     * {@link #start()} call. Use {@link #clear()} to remove the recorded history.
     *
     * @return the read-only view of the recorded changes
     */
    VList<Change> all();

    /**
     * Returns a live, read-only, observable view of the transactions published since
     * the last {@link #start()} call. Use {@link #clear()} to remove the recorded history.
     *
     * @return the read-only view of the published transactions
     */
    VList<Transaction> transactions();

    /**
     * Removes all recorded changes (also removes transactions).
     */
    void clear();

    /**
     * Returns the model version.
     *
     * @return model version
     */
    ModelVersion modelVersion();

    /**
     * Indicates whether model versioning is enabled.
     *
     * @return {@code true} if model versioning is enabled; {@code false}
     * otherwise
     */
    boolean isModelVersioningEnabled();


}
