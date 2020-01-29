package eu.mihosoft.vmf.runtime.core.internal;

/**
 * Data structure to save Nodes and their properties
 */
public class NodeData {
    /**
     * @param object Holds the corresponding Object
     * @param parent Saves the index of the parent Object
     * @param inOrderParent Saves the index of the parent Object in inOrdering
     * @param containment Saves the containment relationship between the Object and its parent
     * @param containmentIndex Saves the index at which an Object is contained in its parent
     */
    private Object object;
    private int parent;
    private int inOrderParent;
    private String containment;
    private int containmentIndex = 0;

    public Object getObject() { return object; }

    public void setObject(Object object) {
        this.object = object;
    }

    int getInorderParent() {
        return inOrderParent;
    }

    void setInorderParent(int inOrderParent) {
        this.inOrderParent = inOrderParent;
    }

    public int getParent() {
        return parent;
    }

    public void setParent(int parent) {
        this.parent = parent;
    }

    public String getContainment() {
        return containment;
    }

    public void setContainment(String containment) {
        this.containment = containment;
    }

    public int getContainmentIndex() { return containmentIndex; }

    public void setContainmentIndex(int containmentIndex) { this.containmentIndex = containmentIndex; }

}
