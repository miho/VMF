package eu.mihosoft.vmf.core;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class ReadOnlyImplementation {

    private final String typeName;
    private final String packageName;
    private final List<Prop> properties = new ArrayList<>();
    private final ModelType type;


    private ReadOnlyImplementation(ModelType type) {
        this.type = type;

        this.packageName = type.getPackageName()+"."+VMFEngineProperties.VMF_IMPL_PKG_EXT;
        this.typeName = type.getReadOnlyInterface().getTypeName()+VMFEngineProperties.VMF_IMPL_CLASS_EXT;

        this.properties.addAll(type.getProperties());

        this.properties.addAll(type.getImplementz().stream().flatMap(t->t.getProperties()
                .stream()).filter(p->!properties.contains(p)).collect(Collectors.toList()));
    }


    public static ReadOnlyImplementation newInstance(ModelType type) {
        return new ReadOnlyImplementation(type);
    }

    public String getTypeName() {
        return typeName;
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

    public int getTypeId() {
        return getType().getTypeId()+1;
    }
}
