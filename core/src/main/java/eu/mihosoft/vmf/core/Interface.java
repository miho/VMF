/*
 * Copyright 2017-2023 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2023 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>Created by miho on 06.01.2017.</p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Deprecated
public final class Interface {

    private final String name;
    private final String packageName;
    private final List<Prop> properties = new ArrayList<>();
    private final List<Prop> propertiesWithoutCollectionsBasedContainment = new ArrayList<>();
    private final ModelType type;

    private Interface(ModelType type) {
        this.type = type;

        this.packageName = type.getPackageName();
        this.name = type.getTypeName();
    }

    public static Interface newInstance(ModelType type) {
        return new Interface(type);
    }

    void initProperties() {

        if(!properties.isEmpty()) {
            throw new RuntimeException("Properties of '" + getName() + "' already initialized!");
        }

        properties.addAll(type.getProperties());

        List<Prop> implProperties = new ArrayList<>();
        implProperties.addAll(computeImplementedProperties(type));

        // only add properties that are not already present in properties
        this.properties.addAll(
            implProperties.stream().filter(p -> !properties.stream()
                    .map(myP->myP.getName())
                    .filter(myPName->myPName.equals(p.getName()))
                    .findAny().isPresent()
                )
                .distinct().collect(Collectors.toList())
        );

//        // filter out duplicates (considering overloaded properties)
//        List<Prop> distinctProperties = Prop.filterDuplicateProps(type, allProps, false);
//        this.properties.addAll(distinctProperties);

        this.propertiesWithoutCollectionsBasedContainment.addAll(
                ModelType.propertiesWithoutCollectionsBasedContainment(
                        this.type, this.properties)
        );
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

    private static List<Prop> computeImplementedProperties(ModelType type) {
        List<Prop> result = new ArrayList<>();
        for(ModelType t: type.getImplementz()) {

            List<Prop> computerProps = computeImplementedProperties(t);
            result.addAll(computerProps);
            // only add properties if they are getter-only and
            //  we are not an interface-only or immutable type
            result.addAll(t.getProperties().stream().
                filter(
                    p->p.isGetterOnly()
                ).collect(Collectors.toList())
            );
        }

        return result;
    }
}
