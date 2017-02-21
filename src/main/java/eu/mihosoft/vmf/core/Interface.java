package eu.mihosoft.vmf.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by miho on 06.01.2017.
 */
public class Interface {

    private final String name;
    private final String packageName;
    private final List<Prop> properties = new ArrayList<>();
    private final List<Prop> propertiesWithoutCollectionsBasedContainment;
    private final ModelType type;

    private Interface(ModelType type) {
        this.type = type;

        this.packageName = type.getPackageName();
        this.name = type.getTypeName();

        this.properties.addAll(type.getProperties());

        this.propertiesWithoutCollectionsBasedContainment =
                ModelType.propertiesWithoutCollectionsBasedContainment(
                        this.type, this.properties);
    }

    public static Interface newInstance(ModelType type) {
        return new Interface(type);
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

    public List<Prop> getPropertiesWithoutCollectionsBasedContainment() {
        return propertiesWithoutCollectionsBasedContainment;
    }
}
