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
