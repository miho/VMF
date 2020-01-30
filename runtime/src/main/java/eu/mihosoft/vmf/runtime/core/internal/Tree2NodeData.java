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

import eu.mihosoft.ext.apted.node.Node;

/**
 * Creates a Node<NodeData> Object to make the Tree compatible for the APTED-Algorithm
 */
public class Tree2NodeData {

    //Create a new Node<NodeData> Object out of the nodeTable
    @SuppressWarnings("unchecked")
    public static Node<NodeData> tree2NodeData(NodeData[] nodeTable){

        //treeNode_List contains the TreeObjects in the order they are present in the indexTable
        int size = nodeTable.length;
        Node[] treeNode_List = new Node[size];

        //Fill the treeNode_List
        for(int i=0; i<size; i++){
            Node<NodeData> treeNode = new Node<>(nodeTable[i]);
            treeNode_List[i] = treeNode;

            //Set the parent-child relation
            int parent_index = nodeTable[i].getParent();
            if(parent_index>-1){
                treeNode_List[parent_index].getChildren().add(treeNode);
            }

        }

        return treeNode_List[0];
    }
}
