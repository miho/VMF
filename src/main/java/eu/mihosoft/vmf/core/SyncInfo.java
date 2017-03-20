package eu.mihosoft.vmf.core;

/**
 * Created by miho on 06.01.2017.
 */
public class SyncInfo {
    private final ModelType thiz;
    private final ModelType other;
    private final Prop opposite;

    private SyncInfo(ModelType thiz, ModelType other, Prop opposite) {
        this.thiz = thiz;
        this.other = other;
        this.opposite = opposite;
    }

    public static SyncInfo newInstance(ModelType thiz, ModelType other, Prop opposite) {
        return new SyncInfo(thiz, other, opposite);
    }

    public ModelType getThiz() {
        return thiz;
    }

    public ModelType getOther() {
        return other;
    }

    public Prop getOpposite() {
        return opposite;
    }

}
