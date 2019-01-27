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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.mihosoft.vmf.runtime.core;

import eu.mihosoft.vcollections.VList;

import vjavax.observer.Subscription;

/**
 * Contains change related functionality of this object graph.
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public interface Changes {

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
     * @param l the listher to add
     * @param recursive determines whether to add the listener recuirsively to all objects of the
     *                  current object graph
     * @return a subscription which allows to unsubscribe the specified listener
     */
    Subscription addListener(ChangeListener l, boolean recursive);



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
     * Returns all changes to the model (observable collection) that were
     * recorded since the last {@link eu.mihosoft.vmf.runtime.core.Changes#start()
     * } call.
     *
     * @return all changes to the model (observable collection) that were
     * recorded since the last {@link eu.mihosoft.vmf.runtime.core.Changes#start()
     * } call
     */
    VList<Change> all();

    /**
     * Returns all model transactions (observable collection) that were
     * published since the last {@link eu.mihosoft.vmf.runtime.core.Changes#start()
     * } call.
     *
     * @return all model transactions (observable collection)
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
