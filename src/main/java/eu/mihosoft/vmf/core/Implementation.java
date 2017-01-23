package eu.mihosoft.vmf.core;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 */
public class Implementation {

    private final String typeName;
    private final String packageName;
    private final List<Prop> properties = new ArrayList<>();
    private final ModelType type;
    private final List<Prop> propertiesWithoutCollectionsBasedContainment;
    private final List<Prop> propertiesForEquals = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();

    private Implementation(ModelType type) {
        this.type = type;

        this.packageName = type.getPackageName()+"."+VMFEngineProperties.VMF_IMPL_PKG_EXT;
        this.typeName = type.getTypeName()+VMFEngineProperties.VMF_IMPL_CLASS_EXT;

        this.properties.addAll(type.getProperties());

        this.properties.addAll(type.getImplementz().stream().flatMap(t->t.getProperties()
                .stream()).filter(p->!properties.contains(p)).collect(Collectors.toList()));

        this.propertiesWithoutCollectionsBasedContainment =
                ModelType.propertiesWithoutCollectionsBasedContainment(this.type, this.properties);

        propertiesForEquals.addAll(properties.stream().
                filter(p->!p.isIgnoredForEquals()).
                filter(p->!p.isContainer()).collect(Collectors.toList()));

        initImports(imports);
    }


    public static Implementation newInstance(ModelType type) {
        return new Implementation(type);
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

    public List<Prop> getPropertiesWithoutCollectionsBasedContainment() {
        return propertiesWithoutCollectionsBasedContainment;
    }

    public List<Prop> getPropertiesForEquals() {
        return propertiesForEquals;
    }

    public List<String> getImports() {
        return imports;
    }

    private void initImports(List<String> imports) {
        if (!imports.isEmpty()) {
            throw new RuntimeException("Already initialized.");
        }

        imports.addAll(properties.stream().map(p -> p.getPackageName()).
                filter(pkg -> !pkg.isEmpty()).filter(pkg -> !"java.lang".equals(pkg)).
                filter(pkg -> !getType().getModel().getPackageName().equals(pkg)).map(imp -> imp + ".*").distinct().
                collect(Collectors.toList()));
    }
}
