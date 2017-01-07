package eu.mihosoft.vmf.playground.sketch;

import javafx.collections.ObservableList;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by miho on 02.01.2017.
 */
interface NodeSpec {

    @Required
    String getName();

    @Contains(opposite = "parent")
    List<Node> getChildren();

    @Container(opposite = "children")
    Node getParent();

    @Container(opposite = "slave")
    Node getMaster();

    @Contains(opposite = "master")
    Node getSlave();
}

/**
 * A representation of the model object {@code eu.mihosoft.vmf.playground.sketch.Node}.
 */
public interface Node extends VObject<WritableNode> {

    /**
     * Returns the value of the '<em>Name</em>' property.
     *
     * <!-- vmf-begin-model-doc -->
     * <!-- vmf-end-model-doc -->
     *
     * @return the value of the '<em>Name</em>' property.
     * @see WritableNode#setName(String)
     */
    String getName();

    /**
     * Returns the value of the '<em>Children</em>' containment list.
     *
     * It is bidirectional and its opposite is '{@link Node#getParent() <em>Parent</em>}'.
     *
     * <!-- vmf-begin-model-doc -->
     * <!-- vmf-end-model-doc -->
     *
     * @return the value of the '<em>Children</em>' containment list.
     */
    List<Node> getChildren();

    /**
     * Returns the value of the '<em><b>Parent</b></em>' container reference.
     *
     * It is bidirectional and its opposite is '{@link Node#getChildren() <em>Children</em>}'.
     *
     * <!-- vmf-begin-model-doc -->
     * <!-- vmf-end-model-doc -->
     *
     * @return the value of the '<em><b>Parent</b></em>' container reference.
     */
    Node getParent();

    /**
     * Returns the value of the '<em><b>Master</b></em>' container reference.
     *
     * It is bidirectional and its opposite is '{@link Node#getSlave() <em>Slave</em>}'.
     *
     * <!-- vmf-begin-model-doc -->
     * <!-- vmf-end-model-doc -->
     *
     * @return the value of the '<em><b>Master</b></em>' container reference.
     * @see WritableNode#setMaster(Node)
     */
    Node getMaster();

    /**
     * Returns the value of the '<em><b>Slave</b></em>' containment reference.
     *
     * It is bidirectional and its opposite is '{@link Node#getMaster() <em>Master</em>}'.
     *
     * <!-- vmf-begin-model-doc -->
     * <!-- vmf-end-model-doc -->
     *
     * @return the value of the '<em><b>Slave</b></em>' containment reference.
     * @see WritableNode#setSlave(Node)
     */
    Node getSlave();

    /**
     * Creates a new instance of '{@link Node <em><b>eu.mihosoft.vmf.playground.sketch.Node</b></em>'}
     * @return a new instance of '{@link Node <em><b>eu.mihosoft.vmf.playground.sketch.Node</b></em>'}
     */
    @Override
    default WritableNode newInstance() {
        NodeImpl result = new NodeImpl();

        return result;
    }

    /**
     * Creates a new instance of '{@link Node <em><b>eu.mihosoft.vmf.playground.sketch.Node</b></em>'}
     * @param name the value of the '<em>Name</em>' property
     * @return a new instance of '{@link Node <em><b>eu.mihosoft.vmf.playground.sketch.Node</b></em>'}
     */
    default WritableNode newInstance(String name) {
        NodeImpl result = new NodeImpl();
        result.setName(name);

        return result;
    }
}

/**
 * A writable representation of the model object {@code eu.mihosoft.vmf.playground.sketch.Node}.
 */
interface WritableNode extends Node {

    /**
     * Sets the value of the '{@link Node#getName() <em>Name</em>}' property.
     *
     * <!-- vmf-begin-model-doc -->
     * <!-- vmf-end-model-doc -->
     *
     * @param name the value of the '{@link Node#getName() <em>Name</em>}' property
     * @see Node#getName()
     */
    void setName(String name);

    // disabled due to @eu.mihosoft.vmf.playground.sketch.Access(publicSetter=false) in eu.mihosoft.vmf.playground.sketch.NodeSpec
    // void setChildren(List<eu.mihosoft.vmf.playground.sketch.Node> children);

    /**
     * Sets the value of the '{@link Node#getMaster() <em>Master</em>}' container reference.
     * @param master the value of the '{@link Node#getMaster() <em>Master</em>}' container reference
     * @see Node#getMaster()
     */
    void setMaster(Node master);

    /**
     * Sets the value of the '{@link Node#getSlave() <em>Slave</em>}' containment reference.
     * @param slave the value of the '{@link Node#getSlave() <em>Slave</em>}' containment reference
     * @see Node#getSlave()
     */
    void setSlave(Node slave);
}

class NodeImpl implements WritableNode {
    private String name;
    private ObservableList<Node> children;
    private Node parent;

    private Node master;
    private Node slave;

    private PropertyChangeSupport propertyChanges;

    public void setName(String name) {

        // set the new value
        String oldValue = this.name;
        this.name = name;

        // fire property change event
        _vmf_firePropertyChangeIfListenersArePresent("name", oldValue, name);
    }

    public String getName() {
        return name;
    }

    public List<Node> getChildren() {
        return VContainmentUtil.asContainmentList(children, "parent");
    }

    @Override
    public Node getParent() {
        return parent;
    }

    @Override
    public Node getMaster() {
        return master;
    }

    @Override
    public Node getSlave() {
        return slave;
    }

    @Override
    public void setMaster(Node master) {

        // set the new value
        Node oldValue = this.master;
        this.master = master;

        // fire property change event
        _vmf_firePropertyChangeIfListenersArePresent("master", oldValue, master);

        // if previous container is present then release containment relation
        if(oldValue!=null) {
            ((NodeImpl)oldValue)._vmf_setSlaveNoContainment(null);
        }

        // if new container is present then update containment relation
        if(master!=null) {
            ((NodeImpl)master)._vmf_setSlaveNoContainment(this);
        }
    }

    private void _vmf_setSlaveNoContainment(NodeImpl slave) {
        this.slave = slave;
    }

    @Override
    public void setSlave(Node slave) {

        // set the new value
        Node oldValue = this.slave;
        this.slave = slave;

        // fire property change event
        _vmf_firePropertyChangeIfListenersArePresent("slave", oldValue, slave);

        // if previous container is present then release containment relation
        if(oldValue!=null) {
            ((NodeImpl)oldValue)._vmf_setMasterNoContainment(null);
        }

        // if new container is present then update containment relation
        if(slave!=null) {
            ((NodeImpl)slave)._vmf_setMasterNoContainment(this);
        }
    }

    private void _vmf_setMasterNoContainment(NodeImpl master) {
        this.master = master;
    }

    // --------------------------------------------------------------------
    // --- Utility methods
    // --------------------------------------------------------------------

    public void addPropertyChangeListener(PropertyChangeListener l) {
        _vmf_getPropertyChanges().addPropertyChangeListener(l);
    }

    public void removePropertyChangeListener(PropertyChangeListener l) {
        _vmf_getPropertyChanges().removePropertyChangeListener(l);

        if(_vmf_getPropertyChanges().getPropertyChangeListeners().length==0) {
            propertyChanges = null;
        }
    }

    private PropertyChangeSupport _vmf_getPropertyChanges() {

        if(propertyChanges==null) {
            propertyChanges = new PropertyChangeSupport(this);
        }

        return propertyChanges;
    }

    private boolean _vmf_hasListeners() {
        return propertyChanges!=null;
    }

    private void _vmf_firePropertyChangeIfListenersArePresent(String propertyName, Object oldValue, Object newValue) {
        if(_vmf_hasListeners()) {
            _vmf_getPropertyChanges().
                    firePropertyChange(propertyName, oldValue, newValue);
        }
    }
}

interface ObservableObject {
    void addPropertyChangeListener(PropertyChangeListener l);
    void removePropertyChangeListener(PropertyChangeListener l);
}

interface VObject<T> extends ObservableObject {
    T newInstance();
}

class VContainmentUtil {
    public static <T> List<T> asContainmentList(List<T> children, String parent) {
        return null;
    }

    public static <T> Set<T> asContainmentSet(Set<T> children, String parent) {
        return null;
    }

    public static <V,T> Map<V,T> asContainmentMap(Map<V,T> children, String parent) {
        return null;
    }
}
