/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class Implementation {

    private final String typeName;
    private final String packageName;
    private final List<Prop> properties = new ArrayList<>();
    private final ModelType type;
    private final List<Prop> propertiesWithoutCollectionsBasedContainment = new ArrayList<>();
    private final List<Prop> propertiesForEquals = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();
    private final List<DelegationInfo> delegations = new ArrayList<>();
    private final List<DelegationInfo> methodDelegations = new ArrayList<>();
    private final List<DelegationInfo> constructorDelegations = new ArrayList<>();
    private final List<AnnotationInfo> annotations = new ArrayList<>();

    private Implementation(ModelType type) {
        this.type = type;

        this.packageName = type.getPackageName()+"."+VMFEngineProperties.VMF_IMPL_PKG_EXT;
        this.typeName = type.getTypeName()+VMFEngineProperties.VMF_IMPL_CLASS_EXT;

        this.properties.addAll(type.getProperties());
    }

    void initPropertiesImportsAndDelegates() {

        List<Prop> implProperties = new ArrayList<>();
        implProperties.addAll(computeImplementedProperties(type));

        // distinct properties ( done via Object.equals() )
        implProperties = implProperties.stream().distinct().collect(Collectors.toList());

        if(type.isCustomPropertyOrderPresent()) {

            // properties of super types go first
            // that's, why we use negative indices for those
            int index = -implProperties.size();

            // find min index used by type properties
            int minIndex = type.getProperties().stream().
                    mapToInt(p->p.getCustomOrderIndex()).min().getAsInt();

            // ensure that we are below min index
            if(minIndex < 0) {
                index = index + minIndex;
            }

            // assign property indices
            for(Prop implProp : implProperties) {
                implProp.setCustomOrderIndex(index);
                index++;
            }
        }

        // only add properties that are not already present in properties
        this.properties.addAll(implProperties.stream().filter(p->!properties.contains(p)).
                distinct().collect(Collectors.toList()));

        // filter out duplicates (considering overloaded properties)
        List<Prop> distinctProperties = Prop.filterDuplicateProps(properties, false);
        this.properties.clear();
        this.properties.addAll(distinctProperties);

        // All delegations
        List<DelegationInfo> delegations = new ArrayList<>(type.getDelegations());
        delegations.addAll(computeImplementedDelegations(type));
        this.delegations.addAll(delegations.stream().distinct().collect(Collectors.toList()));

        // Method delegations
        List<DelegationInfo> methodDelegations = new ArrayList<>(type.getMethodDelegations());
        methodDelegations.addAll(computeImplementedMethodDelegations(type));
        this.methodDelegations.addAll(methodDelegations.stream().distinct().collect(Collectors.toList()));

        // Constructor delegations
        List<DelegationInfo> constructorDelegations = new ArrayList<>(type.getConstructorDelegations());
        constructorDelegations.addAll(computeImplementedConstructorDelegations(type));
        this.constructorDelegations.addAll(constructorDelegations.stream().distinct().collect(Collectors.toList()));

        // set all annotations (distinct)
        List<AnnotationInfo> allAnnotations = new ArrayList<>(type.getAnnotations());
//        allAnnotations.addAll(computeInheritedAnnotations(type));
        this.annotations.addAll(allAnnotations);

        // we require alphabetic order (by key)
        Collections.sort(this.annotations, Comparator.comparing(AnnotationInfo::getKey));

        // sort properties
        ModelType.sortProperties(properties, type.isCustomPropertyOrderPresent());

        this.propertiesWithoutCollectionsBasedContainment.addAll(
                ModelType.propertiesWithoutCollectionsBasedContainment(this.type, this.properties));

        propertiesForEquals.addAll(properties.stream().
                filter(p->!p.isIgnoredForEquals()).
                filter(p->!p.isContainer()).collect(Collectors.toList()));

        initImports(imports);
    }

    private static List<Prop> computeImplementedProperties(ModelType type) {
        List<Prop> result = new ArrayList<>();
        for(ModelType t: type.getImplementz()) {
            result.addAll(computeImplementedProperties(t));
            result.addAll(t.getProperties());
        }

        return result;
    }

    private static List<DelegationInfo> computeImplementedDelegations(ModelType type) {
        List<DelegationInfo> result = new ArrayList<>();
        for(ModelType t: type.getImplementz()) {
            result.addAll(t.getDelegations());
            result.addAll(computeImplementedDelegations(t));
        }

        return result;
    }

    private static List<DelegationInfo> computeImplementedMethodDelegations(ModelType type) {
        List<DelegationInfo> result = new ArrayList<>();
        for(ModelType t: type.getImplementz()) {
            result.addAll(t.getMethodDelegations());
            result.addAll(computeImplementedMethodDelegations(t));
        }

        return result;
    }

    private static List<DelegationInfo> computeImplementedConstructorDelegations(ModelType type) {
        List<DelegationInfo> result = new ArrayList<>();
        for(ModelType t: type.getImplementz()) {
            result.addAll(t.getConstructorDelegations());
            result.addAll(computeImplementedConstructorDelegations(t));
        }

        return result;
    }

//    private static List<AnnotationInfo> computeInheritedAnnotations(ModelType type) {
//        List<AnnotationInfo> result = new ArrayList<>();
//        for(ModelType t: type.getImplementz()) {
//            result.addAll(computeInheritedAnnotations(t));
//            result.addAll(t.getAnnotations());
//        }
//
//        return result;
//    }

    public List<DelegationInfo> getDelegations() {
        return delegations;
    }

    public List<DelegationInfo> getDelegationsOneForEachType() {
        return delegations.stream().filter(distinctByKey(d->d.getFullTypeName())).collect(Collectors.toList());
    }

    public List<DelegationInfo> getMethodDelegations() {
        return methodDelegations;
    }

    public List<DelegationInfo> getConstructorDelegations() {
        return constructorDelegations;
    }


    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object,Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    public List<AnnotationInfo> getAnnotations() {
        return annotations;
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
