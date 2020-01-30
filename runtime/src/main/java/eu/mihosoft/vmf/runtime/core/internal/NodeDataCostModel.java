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
/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.ext.apted.costmodel.CostModel;
import eu.mihosoft.ext.apted.node.Node;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Objects;

/**
 * Defines the cost for all changes and therefor directs the mapping
 */
public class NodeDataCostModel implements CostModel<NodeData>{
    /**
     * @param n Input nodeData
     * @return Deletion of a Node always costs "1"
     */
        public float del(Node<NodeData> n) {
            return 1.0f;
        }

    /**
     * @param n Input nodeData
     * @return Insertion of a Node always costs "1"
     */
        public float ins(Node<NodeData> n) {
            return 1.0f;
        }

    /**
     * Renaming a node means looking for differing properties and exchanging them
     *
     * @param n1 Input nodeData of the original Object
     * @param n2 Input nodeData of the clone Object
     * @return The cost of renaming
     *
     * renFactor: The cost of a single renaming operation
     * newCost: will contain the final renaming cost
     */
        public float ren(Node<NodeData> n1, Node<NodeData> n2) {
            float renFactor = 0.2f;
            float newCost = 0f;

            /*
             * If Objects are not of the same class the cost is maximal, since they can't be renamed
             * We force del/ins operations instead.
             */
            if(!Objects.equals(n2.getNodeData().getObject().getClass(),
                    n1.getNodeData().getObject().getClass())){
                return Float.MAX_VALUE;
            } else{
                /*
                 * Objects are of same class
                 */
                Object n1_obj = n1.getNodeData().getObject();
                int n1_propSize = ((VObject) n1_obj).vmf().reflect().properties().size();
                /*
                 * We assume that if n1 and n2 are of same class they have the same # of properties
                 */
                for (int i = 0; i < n1_propSize; i++) {
                    Property n1_prop = ((VObject) n1.getNodeData().getObject()).vmf().reflect().properties().get(i);
                    Property n2_prop = ((VObject) n2.getNodeData().getObject()).vmf().reflect().properties().get(i);
                    /*
                     * We make the renaming a little more expensive for every property that differs
                     */
                    if (!Objects.equals(n1_prop.get(), n2_prop.get())
                            && !n1_prop.getType().isListType()
                            && !n2_prop.getType().isListType()
                            && !n1_prop.getType().isModelType()
                            && !n2_prop.getType().isModelType()) {
                        newCost += renFactor;
                    }
                }
            }
            return newCost;
        }

}
