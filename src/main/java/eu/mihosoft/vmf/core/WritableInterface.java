package eu.mihosoft.vmf.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 */
public class WritableInterface {

    private final String name;
    private final String packageName;
    private final List<Prop> properties = new ArrayList<>();
    private final ModelType type;

    private WritableInterface(ModelType type) {
        this.type = type;

        this.packageName = type.getPackageName();
        this.name = type.getTypeName();

        System.out.println(type.resolveProp("children").get().isContainmentProperty());

        Predicate<Prop> isContainmentProp = p->p.isContainmentProperty();
        Predicate<Prop> isCollectionType = p->p.getPropType()==PropType.COLLECTION;
        Predicate<Prop> oppositeIsCollectionType = p->p.isContainmentProperty()
                &&p.getContainmentInfo().getOpposite().getPropType()==PropType.COLLECTION;

        this.properties.addAll(type.getProperties().stream().
                filter(p->isContainmentProp.and(isCollectionType).negate().test(p)).
                filter(oppositeIsCollectionType.negate()).
                collect(Collectors.toList()));
    }

    public static WritableInterface newInstance(ModelType type) {
        return new WritableInterface(type);
    }

    public String getName() {
        return name;
    }

    public String getPackageName() {
        return packageName;
    }

    public List<Prop> getProperties() {
        return properties;
    }

    public ModelType getType() {
        return type;
    }
}
