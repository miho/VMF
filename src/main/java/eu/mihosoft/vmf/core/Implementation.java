package eu.mihosoft.vmf.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by miho on 06.01.2017.
 */
public class Implementation {

    private final String name;
    private final String packageName;
    private final List<Prop> properties = new ArrayList<>();
    private final ModelType type;

    private Implementation(ModelType type) {
        this.type = type;

        this.packageName = type.getPackageName();
        this.name = type.getTypeName();

        this.properties.addAll(type.getProperties());
    }

    public static Implementation newInstance(ModelType type) {
        return new Implementation(type);
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
