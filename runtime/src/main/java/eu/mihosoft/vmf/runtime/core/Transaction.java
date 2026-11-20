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

import java.util.List;

/**
 * A collection of changes. Transactions can be used to describe higher-level model edits.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public interface Transaction {

    /**
     * Returns the atomic changes that are part of this transaction.
     * @return an unmodifiable list of the transaction's changes in recording order
     */
    List<Change> changes();

    /**
     * Checks the most recent change for each affected object and property against the live model.
     * Earlier changes are checked as they are undone. This check assumes the transaction
     * is being undone in reverse order and that intervening writes have already been undone.
     * @return {@code true} if the latest changes pass the undo checks
     */
    boolean isUndoable();

    /**
     * Undoes the changes in reverse order when the initial undo checks pass.
     * Undo is not atomic. Generated reciprocal mutations or synchronous observers
     * may affect later checks, and a failure can leave some changes already undone.
     */
    void undo();
}

