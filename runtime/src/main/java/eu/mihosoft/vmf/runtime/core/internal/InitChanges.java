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

        DiffingImpl.change();
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
