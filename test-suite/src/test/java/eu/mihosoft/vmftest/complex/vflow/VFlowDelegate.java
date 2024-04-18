/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
package eu.mihosoft.vmftest.complex.vflow;

import java.util.Objects;
import java.util.Collection;
import java.util.List;

import eu.mihosoft.vcollections.EventUtil;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.*;
import vjavax.observer.collection.CollectionChange;
import vjavax.observer.collection.CollectionChangeListener;

public class VFlowDelegate implements DelegatedBehavior<VFlow> {
    private VFlow caller;

    public void setCaller(VFlow caller) {
        this.caller = caller;
    }

    public void onVFlowInstantiated() {

        // prevent duplicates & set id
        caller.getNodes().addChangeListener(
                (CollectionChangeListener<VNode, Collection<VNode>, CollectionChange<VNode>>) evt -> {
                    for (VNode n : evt.added().elements()) {
                        // if(caller.getNodes().stream().filter(m->Objects.equals(n,m)).count()>1) {
                        if (caller.getNodes().stream().filter(m -> n == m).count() > 1) {
                            throw new RuntimeException("Duplicate nodes added: " + n);
                        }
                    }
                });
    }

    public ConnectionResult connect(Connector c1, Connector c2) {
        return c1.connect(c2);
    }

    public ConnectionResult tryConnect(Connector c1, Connector c2) {
        return c1.tryConnect(c2);
    }

    public ConnectionResult connect(VNode n1, VNode n2, String type) {
        return null;
    }

    public VNode newNode(Object o) {
        VNode node = VNode.newInstance();
        node.setValue(o);
        caller.getNodes().add(node);

        return node;
    }

    public VFlow newSubFlow(Object o) {
        VFlow node = VFlow.newInstance();
        node.setValue(o);
        caller.getNodes().add(node);

        return node;
    }
}
