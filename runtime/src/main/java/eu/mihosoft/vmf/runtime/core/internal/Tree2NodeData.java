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
