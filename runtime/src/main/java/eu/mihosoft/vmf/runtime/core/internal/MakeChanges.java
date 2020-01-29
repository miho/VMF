package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vmf.runtime.core.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MakeChanges {
    static private VObject input;
    static public VObject target;
    private static VObject input_deepCopy;

    static private NodeData[] nodeTable_input;
    static private NodeData[] nodeTable_target;
    static private List<int[]> map;

    static private NodeData[] newTable;

    static private List<Change> allChangesList;
    static private boolean printChanges = false;

    /*  The function change() has to be called with two VObjects and their index-Tables in inorder, as well as
     * a mapping between the two Objects. change() then calls the function deleteChange() to remove all Objects
     * that are present in the original Object, but not in the clone. addChange() is called to add all Objects
     * present in the clone which did not exists in the original. renameChange() can slightly modify Objects if
     * the changes to be made are not too serious (as determined in the NodeDataCostModel).
     *
     * @param input_object The input object
     * @param target_object The target object for which the dif is created
     */
    static void change(VObject input_object, VObject target_object) {
        input = input_object;
        input_deepCopy = input.vmf().content().deepCopy();
        target = target_object;

        nodeTable_input = InitChanges.getIndexTable_original_inorder();
        nodeTable_target = InitChanges.getIndexTable_clone_inorder();
        map = InitChanges.getMapping();
        allChangesList = new ArrayList<>();

        VIterator count_it = target.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        int cloneSize = (int) count_it.asStream().count();

        /*
        newTable will contain all new nodes that are needed to create the new Object.
        */
        newTable = new NodeData[cloneSize];
        for (int[] i : map) {
            if (i[1] != 0 && i[0] != 0) {
                newTable[i[1]-1] = nodeTable_input[i[0]-1];
                newTable[i[1]-1].setInorderParent(nodeTable_target[i[1]-1].getInorderParent());
            }
        }

        /*
        Perform all deletions first.
        */
        for (int[] i : map) {
            if (i[1] == 0) {
                deleteChange(i[0]);
            }
        }

        /*
        Create all new nodes. They are saved in the newTable.
        The relationships are not set yet.
         */
        for (int[] i : map) {
            if (i[0] == 0) {
                createNodes2Add(i[1]);
            }
        }



        /*
        Now perform all insertions and set relationships accordingly. Then perform all
        renaming tasks. To capture all changes correctly the change-recorder has to be set
        to the according current Object where relationships are being set. This happens directly
        inside the setRelations() and renameChange() methods.
        */
        setRelations();
        renameChange();

        /*
        We revert all changes. The changes will only be applied when
        applyChange() is called.
        */
        List<Change> revertList = new ArrayList<>(allChangesList);

        Collections.reverse(revertList);
        revertList.forEach(
                evt -> {
                    evt.undo(); //@TODO:: Check if undoable?
                }
        );

        //@TODO:: Is necessary but might be corrupting changelist
        input = (VObject) nodeTable_input[nodeTable_input.length-1].getObject();
    }

    /**
     * Remove nodes from the input object
     * @param indexDeleteNode index of the node that has to be deleted
     */
    private static void deleteChange(int indexDeleteNode) {
        AtomicReference<VObject> nodeToDelete
                = new AtomicReference<>((VObject) nodeTable_input[indexDeleteNode - 1].getObject());

        if(indexDeleteNode == nodeTable_input.length){
            /*
                This is the case if the root gets deleted.
                We only allow this if the child of the root is distinct.
                If we find multiple child nodes the tree would split when deleting the root,
                we cannot allow this case.
             */
            AtomicBoolean childSet = new AtomicBoolean(false);
            VObject rootNode = (VObject) nodeTable_input[indexDeleteNode - 1].getObject();
            rootNode.vmf().reflect().properties().forEach(
                    property -> {
                        if (property.annotationByKey("vmf_contains").isPresent()) {
                            if (property.getType().isListType()) {
                                if(((List) property.get()).size() == 1 && childSet.get()) {
                                    throw new RuntimeException( "Deletion of Root causes splitting of Tree." );
                                }
                                if(((List) property.get()).size() > 1) {
                                    throw new RuntimeException( "Deletion of Root causes splitting of Tree." );
                                }
                                if(((List) property.get()).size() == 1 && !childSet.get()) {
                                    nodeToDelete.set((VObject) ((List) property.get()).get(0));
                                    childSet.set(true);
                                }
                            } else{
                                if(property.get() != null && !childSet.get()){
                                    nodeToDelete.set((VObject) property.get());
                                    childSet.set(true);
                                }
                                if(property.get() != null && childSet.get()){
                                    throw new RuntimeException( "Deletion of Root causes splitting of Tree." );
                                }
                            }
                        }
                    }
            );
            input = nodeToDelete.get();
        }
        else {
            /*
                For any other case we simply unset the parent node.
                We register a change as a deletion.
            */
            nodeToDelete.get().vmf().reflect().properties().forEach(
                    property -> {
                        /*
                            In case of deletion of an intermediate Node we need to make
                            sure, that all children of "nodeToDelete" get deleted as well.
                         */

                        if (property.annotationByKey("vmf_contains").isPresent()) {
                            if (property.getType().isListType()) {
                                while(!((List) property.get()).isEmpty()){
                                    Object obj = ((List) property.get()).get(0);
                                    nodeToDelete.get().vmf().changes().start();
                                    ((List) property.get()).remove(obj);
                                    nodeToDelete.get().vmf().changes().stop();
                                    allChangesList.addAll(nodeToDelete.get().vmf().changes().all());
                                }
                            } else {
                                nodeToDelete.get().vmf().changes().start();
                                property.unset();
                                nodeToDelete.get().vmf().changes().stop();
                            }
                        }

                        /*
                            Here we delete the parent of "nodeToDelete"
                         */

                        if (property.annotationByKey("vmf_container").isPresent()) {
                            if (property.getType().isListType()) {
                                throw new RuntimeException("Parent is of List Type.");
                            } else {
                                VObject parentObject = (VObject) property.get();
                                parentObject.vmf().changes().start();
                                property.unset();
                                parentObject.vmf().changes().stop();
                                allChangesList.addAll(parentObject.vmf().changes().all());
                            }
                        }
                    }
            );
        }
    }

    /**
     * Set the parent/child relations
     */
    @SuppressWarnings("unchecked")
    private static void setRelations(){
        for (int i = 0; i < newTable.length-1; i++) {
            Object node = newTable[i].getObject();
            VObject root = (VObject) nodeTable_input[nodeTable_input.length-1].getObject();
            int parent = newTable[i].getInorderParent();
            int containmentIndex = nodeTable_target[i].getContainmentIndex();
            String containment = newTable[i].getContainment();

            /*
            If a node is in new intermediate Node we need to record some extra changes.
            For example: Map {A{C}} -> {A{B{C}}}
            Here node "B" will be inserted between "A" and "C". This happens in two steps.
            First delete "C" from "A", then add it to "B". We therefor need a recorder on both
            nodes "A" and "B". We simply start recording on the (old) root node. Since the intermediate
            node has not yet been added to the root, there won't be any duplications
            regarding the changes.
             */
            boolean intermediateNode = false;
            for (int[] j : map) {
                if(j[1] == parent && j[0] == 0 && j[1]!=newTable.length){
                    intermediateNode = true;
                    root.vmf().changes().start();
                }
            }
            /*
            If a node is a former root-node it has no containment. Therefor we need to set it.
             */
            if(containment.isEmpty()){
                newTable[i].setContainment(nodeTable_target[i].getContainment());
                containment = newTable[i].getContainment();
            }

            final String fin_containment = containment;
            VObject parentNode = (VObject) newTable[parent - 1].getObject();
            parentNode.vmf().changes().start();
            parentNode.vmf().reflect().properties().forEach(
                    property -> {
                        if (property.annotationByKey("vmf_contains").isPresent()
                                && property.annotationByKey("vmf_contains").get().getValue().equals(fin_containment)) {
                            if (property.getType().isListType()) {
                                /*
                                Add if not already contained.
                                The Node is added at the correct index, but we need to check whether the index
                                is not out of bound.
                                 */
                                if (!((List) property.get()).contains(node)) {
                                    int currentLength = ((List) property.get()).size();
                                    if(containmentIndex-1 > currentLength){
                                        ((List<Object>) property.get()).add(node);
                                    }
                                    else {
                                        ((List<Object>) property.get()).add(containmentIndex, node);
                                    }
                                }
                            } else {
                                //Change if property is not already set the the object
                                if (!Objects.equals(property.get(), node)) {
                                    property.set(node);
                                }
                            }
                        }
                    }
            );

            if(intermediateNode){
                root.vmf().changes().stop();
                allChangesList.addAll(root.vmf().changes().all());
            }

            parentNode.vmf().changes().stop();
            allChangesList.addAll(parentNode.vmf().changes().all());
        }
    }

    /**
     * Creates new nodes if necessary
     * @param ind Index from the mapping
     */
    private static void createNodes2Add(int ind) {
        /* Get all properties of the new Element we want to add.*/
        VObject cloneObject = (VObject) nodeTable_target[ind-1].getObject();
        String cloneContainment = nodeTable_target[ind-1].getContainment();
        int cloneInOrdInd = nodeTable_target[ind-1].getInorderParent();
        int containmentIndex = nodeTable_target[ind-1].getContainmentIndex();

        /*
         * Create a new node with given properties
         * Set all properties except for containments (Parents/Children)
         */
        Type cloneType = VMFUtil.getType(cloneObject);
        VObject newNode = null;
        if (cloneType != null) {
            newNode = VMFUtil.newInstance(cloneType);
            List<Property> newProp = newNode.vmf().reflect().properties();
            List<Property> cloneProp = cloneObject.vmf().reflect().properties();
            int numProp = cloneProp.size();
            for(int j=0; j<numProp; j++) {
                if (!cloneProp.get(j).annotationByKey("vmf_contains").isPresent()
                    && !cloneProp.get(j).annotationByKey("vmf_container").isPresent()) {
                    newProp.get(j).set(cloneProp.get(j).get());
                }
            }
        }

        NodeData newElem = new NodeData();
        newElem.setObject(newNode);
        newElem.setInorderParent(cloneInOrdInd);
        newElem.setContainment(cloneContainment);
        newElem.setContainmentIndex(containmentIndex);
        newTable[ind-1] = newElem;
    }

    /**
     * Perform rename operations
     */
    private static void renameChange(){
        for (int[] i : map) {
            if (i[0] != 0 && i[1] != 0 &&
                    !Objects.equals(nodeTable_input[i[0]-1].getObject(), nodeTable_target[i[1]-1].getObject())) {

                VObject object = (VObject) nodeTable_input[i[0]-1].getObject();
                List<Property> prop_or = ((VObject) nodeTable_target[i[1]-1].getObject())
                        .vmf().reflect().properties();
                List<Property> prop = object.vmf().reflect().properties();
                object.vmf().changes().start();
                for(int j=0; j<prop.size(); j++){
                    if(!Objects.equals(prop_or.get(j).get(),prop.get(j).get())
                            && !prop.get(j).getType().isModelType()
                            && !prop.get(j).getType().isListType()){
                        prop.get(j).set(prop_or.get(j).get());
                    }
                }
                object.vmf().changes().stop();
                allChangesList.addAll(object.vmf().changes().all());
            }
        }
    }

    /**
     * Print step by step solution
     * @param doPrint print if true
     * @return boolean, whether the changes were successful
     */
    public static boolean applyChange(boolean doPrint) {
        printChanges = doPrint;
        return applyChange();
    }

    /**
     * Applying the changes from the changes list onto the input object
     * @return boolean, whether the changes were successful
     */
    public static boolean applyChange() {

        //Print method --> Show all changes on console
        if(printChanges){
            System.out.println("---------------- Apply changes: ------------------");
            System.out.println(" ");
            System.out.println("Before Changes: " + input);
            System.out.println(" ");
            allChangesList.forEach(c-> {
                System.out.println("Change --> ");
                System.out.println(input);
                System.out.println(" ");
            });
        }

        if(! input.toString().equals(input_deepCopy.toString())){
            throw new RuntimeException( "Problem occurred while undoing Changes." );
        }

        //Apply all changes
        allChangesList.forEach(c-> {
            c.apply(c.object());
        });

        //@TODO: Setting the correct root - Is not recorded into change object
        //VObject rootNode = (VObject) newTable[newTable.length-1].getObject();
        input = (VObject) newTable[newTable.length-1].getObject();

        if(printChanges) {
            System.out.println("--------------- Changes Correct? -----------------");
            System.out.println(" ");
            System.out.println("Original: " + input);
            System.out.println("Clone: " + target);
            System.out.println("Original equals clone?: " + input.toString().equals(target.toString()));
        }
        input.vmf().changes().clear();

        /*
            If the diffing failed we can retry the procedure.
            For example if objects are in the wrong order in a List, this will help.
         */
        if(!input.toString().equals(target.toString())){
            int numRetries = 10;
            for(int i=0; i<numRetries; i++){
                InitChanges.init(input, target);
                MakeChanges.change(input, target);
                MakeChanges.applyChange();
            }
        }
        return input.toString().equals(target.toString());
    }

    /**
     * @return allChangesList
     */
    static List<Change> getChanges() {
        return allChangesList;
    }

    /**
     * Print the changes List onto the console
     */
    public static void printChanges() {
        int num_changes = allChangesList.size();

        System.out.println("---------------- Changes List: --------------------");
        System.out.println("#changes: " + num_changes+"\n");

        allChangesList.forEach(
                (evt)-> {
                    System.out.println("evt: " + evt.propertyName());
                    if(evt.propertyChange().isPresent()) {
                        System.out.println("  -> oldValue: " + evt.propertyChange().get().oldValue());
                        System.out.println("  -> newValue: " + evt.propertyChange().get().newValue());
                        System.out.println(" ");
                    } else if (evt.listChange().isPresent()) {
                        System.out.println("  -> " + evt.listChange().get().toStringWithDetails());
                    }
                }
        );
    }
}

