package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.VMF;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.*;

/**
 * Class to generate nodeTables. A nodeTable is a List of NodeData-Objects and therefor saves
 * each Node present in the inputTree separately. This structure helps to save the important
 * containment information and simplifies access to specific Nodes in the tree.
 *
 * E.g. If given the index i of a node we can simply call the Object by accessing the according
 * spot of the nodeTable: nodeTable[i].getObject()
 */
public class GenerateNodeTable {

    /**
     * Constructs a nodeTable.
     * @param inputTree VMF-Objects that gets mapped onto a nodeTable
     * @return The nodeTable in the ordering picked up by the CONTAINMENT_TREE iterator.
     */
    public static NodeData[] generate(VMF inputTree) {

        VIterator input_it = inputTree.content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        VIterator count_it = inputTree.content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        int n_iterations = (int) count_it.asStream().count();

        NodeData[] nodeTable= new NodeData[n_iterations];

        Object parent_object = input_it.next();

        NodeData parent = new NodeData();
        parent.setObject(parent_object);
        //Root has no parent: We initialize with -1
        parent.setParent(-1);
        parent.setContainment("");
        nodeTable[0] = parent;

        int index = 1;

        while(input_it.hasNext()){
            VObject next_el = input_it.next();
            NodeData current_element = new NodeData();
            //Save new element in indexTable[i]
            current_element.setObject(next_el);

            /*
                Iterate the properties and look for the object with the "parent" annotation.
                Save this element as the parent for the current element.
             */
            final int current_index = index;
            next_el.vmf().reflect().properties().forEach(
                    property -> {
                        if(property.annotationByKey("vmf_container").isPresent()){

                            // Find the parent Object and set the parent_index
                            for(int i=0; i< current_index; i++){
                                if(Objects.equals(nodeTable[i].getObject(),property.get())){
                                    current_element.setParent(i);
                                }
                            }
                            // Iterate over all containers
                            ((VObject) property.get()).vmf().reflect().properties().forEach(
                                    parent_property -> {
                                        if(parent_property.annotationByKey("vmf_contains").isPresent()) {
                                            // Check if containment is List Type and look for matching Object
                                            if(parent_property.getType().isListType() &&
                                                    ((List) parent_property.get()).contains(next_el)){
                                                for(int i=0; i<((List) parent_property.get()).size(); i++){
                                                    if(((List) parent_property.get()).get(i).equals(next_el)){
                                                        current_element.setContainmentIndex(i);
                                                    }
                                                }
                                                String parent_annotation_value = parent_property
                                                        .annotationByKey("vmf_contains").get().getValue();
                                                current_element.setContainment(parent_annotation_value);
                                            } else if(Objects.equals(parent_property.get(),next_el)){
                                                String parent_annotation_value = parent_property
                                                        .annotationByKey("vmf_contains").get().getValue();
                                                current_element.setContainment(parent_annotation_value);
                                            }

                                        }
                                    }
                            );
                        }
                    });
            /*
            next_el.vmf().reflect().properties().forEach(
                    property -> {
                        if(property.annotationByKey("vmf_contains").isPresent()){
                            if(property.getType().isListType()){
                                int numChildren = ((List) property.get()).size();
                                System.out.println(current_element.getObject());
                                System.out.println("!!!!" + numChildren);
                                current_element.setNumChildren(numChildren);
                            }
                        }
                    }
            );
            */
            index += 1;

            nodeTable[current_index] = current_element;
        }
        return nodeTable;
    }

    /**
     * Since APTED uses an inOrdering for its Algorithm the nodeTable needs to be
     * transformed accordingly
     * @param inputTable nodeTable in ordering as given by the iterator
     * @return inOrdered nodeTable
     */
    public static NodeData[] transform_inOrder(NodeData[] inputTable){
        ArrayDeque<NodeData> current_queue = new ArrayDeque<>();
        Stack<NodeData> buffer_stack = new Stack<>();
        int size = inputTable.length;

        NodeData[] inOrderTable = new NodeData[size];


        for(int i=0; i<size; i++){
            if(i<(size-1)){
                if(inputTable[i].getParent() == inputTable[i+1].getParent()){
                    current_queue.add(inputTable[i]);
                }
                else if(inputTable[i].getParent() > inputTable[i+1].getParent()){
                    current_queue.add(inputTable[i]);
                    while( buffer_stack.peek().getParent() >= inputTable[i+1].getParent()){
                        current_queue.add(buffer_stack.pop());
                    }
                }
                else{
                    buffer_stack.push(inputTable[i]);
                }
            }
            else{
                buffer_stack.push(inputTable[i]);
                while(!buffer_stack.empty()){
                    current_queue.add(buffer_stack.pop());
                }
            }
        }

        for(int i=0; i<size; i++){
            inOrderTable[i] = current_queue.getFirst();
            current_queue.removeFirst();
        }

        return setInorderParents(inOrderTable, inputTable);
    }

    /**
     * Help function for transform_inOrder() to changes the indices of the parents
     * to the according inOrdering
     * @param inOrderTable Table in inOrdering (parents not yet set)
     * @param originalTable Table as originally given
     * @return inOrdered nodeTable with correct parent-indices
     */
    private static NodeData[] setInorderParents(
            NodeData[] inOrderTable,
            NodeData[] originalTable){
        for(NodeData elem: inOrderTable){
            int parentIndex = elem.getParent();
            if(parentIndex == -1){
                elem.setInorderParent(0);
            } else {
                Object parentObject = originalTable[parentIndex].getObject();
                int inOrderIndex = 1;
                for(NodeData parentElem: inOrderTable){
                    if(Objects.equals(parentElem.getObject(), parentObject)){
                        elem.setInorderParent(inOrderIndex);
                    }
                    inOrderIndex += 1;
                }
            }


        }
        return inOrderTable;
    }
}
