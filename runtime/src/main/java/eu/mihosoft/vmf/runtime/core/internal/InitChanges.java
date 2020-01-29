package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.ext.apted.distance.APTED;
import eu.mihosoft.ext.apted.node.Node;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.List;

/**
 * The InitChanges() class sets up all required structures to compute the mapping.
 */
public class InitChanges {
    /**
     * @param nodeTable_input_inorder Contains the inordered nodeTable for the input object
     * @param nodeTable_target_inorder Contains the inordered nodeTable for the target object
     * @param mapping Contains the mapping between the two objects
     * @param editDistance Number of edit steps needed
     * @param printMapping Boolean whether to print out the mapping
     */
    static private NodeData[] nodeTable_input_inorder;
    static private NodeData[] nodeTable_target_inorder;
    static private List<int[]> mapping;
    static private int editDistance;

    /**
     * @param input The original objects that needs to be changed
     * @param target The objects that contains the changes to be made
     */
    static public void init(VObject input, VObject target) {
        NodeData[] nodeTableInput = GenerateNodeTable.generate(input.vmf());
        NodeData[] nodeTableTarget = GenerateNodeTable.generate(target.vmf());
        /*
         * Generating the nodeTable
         */
        nodeTable_input_inorder
                = GenerateNodeTable.transform_inOrder(nodeTableInput);
        nodeTable_target_inorder
                = GenerateNodeTable.transform_inOrder(nodeTableTarget);

        /*
         * Constructing the NodeData
         */
        Node<NodeData> t1 = Tree2NodeData.tree2NodeData(nodeTableInput);
        Node<NodeData> t2 = Tree2NodeData.tree2NodeData(nodeTableTarget);

        /*
         * Initializing apted (compute mapping and distance)
         */
        APTED<NodeDataCostModel, NodeData> apted = new APTED<>(new NodeDataCostModel());
        editDistance = (int)apted.computeEditDistance(t1, t2);
        apted.init(t1, t2);
        mapping = apted.computeEditMapping();

        MakeChanges.change(input, target);
    }

    /**
     * @return Returns the inordered nodeTable for the original Object
     */
    static NodeData[] getIndexTable_original_inorder(){
        return nodeTable_input_inorder;
    }

    /**
     * @return Returns the inordered nodeTable for the clone Object
     */
    static NodeData[] getIndexTable_clone_inorder(){
        return nodeTable_target_inorder;
    }

    /**
     * @return Returns the mapping
     */
    static List<int[]> getMapping(){
        return mapping;
    }

    /**
     * @return editDistance
     */
    static int getEditDistance(){
        return editDistance;
    }
}
