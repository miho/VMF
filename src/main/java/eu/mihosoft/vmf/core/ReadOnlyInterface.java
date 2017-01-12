package eu.mihosoft.vmf.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by miho on 06.01.2017.
 */
public class ReadOnlyInterface {

    private final String name;
    private final String packageName;
    private final List<Prop> properties = new ArrayList<>();
    private final ModelType type;

    private ReadOnlyInterface(ModelType type) {
        this.type = type;

        this.packageName = type.getPackageName();
        this.name = type.getTypeName();

        this.properties.addAll(type.getProperties());
    }


    public static ReadOnlyInterface newInstance(ModelType type) {
        return new ReadOnlyInterface(type);
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

    public String getTypeName() { return "ReadOnly" + type.getTypeName();}
}
