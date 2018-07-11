/*
 * Copyright 2017-2018 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2018 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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

/**
 * Created by miho on 06.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
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

    public String getTypeName() {
        // ReadOnly type is only generated if the type is not immutable. Otherwise, the read-only type name
        // is identical to the normal (mutable) type name
        return (type.isImmutable()?"":"ReadOnly") + type.getTypeName();
    }

    public String getFullTypeName() {
        return getPackageName()+"."+getTypeName();
    }
}
