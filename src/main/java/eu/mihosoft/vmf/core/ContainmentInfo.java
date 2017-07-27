package eu.mihosoft.vmf.core;

/**
 * Created by miho on 06.01.2017.
 */
public class ContainmentInfo {
    private final ModelType thiz;
    private final Prop thisProp;
    private final ModelType other;
    private final Prop opposite;
    private final ContainmentType containmentType;

    private ContainmentInfo(ModelType thiz, Prop thisProp, ModelType other, Prop opposite, ContainmentType containmentType) {
        this.thiz = thiz;
        this.thisProp = thisProp;
        this.other = other;
        this.opposite = opposite;
        this.containmentType = containmentType;
    }

    public static ContainmentInfo newInstance(ModelType thiz, Prop thisProp, ModelType other, Prop opposite, ContainmentType containmentType) {
        return new ContainmentInfo(thiz, thisProp, other, opposite, containmentType);
    }

    public ModelType getThiz() {
        return thiz;
    }

    public Prop getThisProp() { return thisProp; }

    public ModelType getOther() {
        return other;
    }

    public Prop getOpposite() {
        return opposite;
    }

    public ContainmentType getContainmentType() {
        return containmentType;
    }

}
