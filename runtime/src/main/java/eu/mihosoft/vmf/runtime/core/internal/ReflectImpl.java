/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vmf.runtime.core.Annotation;
import eu.mihosoft.vmf.runtime.core.Reflect;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * Created by miho on 18.06.2018.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
public class ReflectImpl implements Reflect {

    private VObject model;
    private List<Property> properties;
    private List<Annotation> annotations;
    private boolean staticOnly;
    private Class<?> modelAPIClass;

    public void setModel(VObject model) {
        this.model = model;
    }

    public void setModelAPIClass(Class<?> modelAPIClass) {
        this.modelAPIClass = modelAPIClass;
    }

    public void setStaticOnly(boolean staticOnly) {
        this.staticOnly = staticOnly;
    }

    @Override
    public List<Annotation> annotations() {
        if (annotations == null) {
            VObjectInternal parent = (VObjectInternal) model;

            annotations = new ArrayList<>(parent._vmf_getAnnotations());
        }

        return annotations;
    }

    @Override
    public List<Property> properties() {

        if (properties == null) {

            VObjectInternal parent = (VObjectInternal) model;

            properties = new ArrayList<>(parent._vmf_getPropertyNames().length);

            for (String pName : parent._vmf_getPropertyNames()) {
                properties.add(Property.newInstance(parent, pName, staticOnly));
            }
        }

        return properties;
    }

    @Override
    public Type type() {
        VObjectInternal vObj = (VObjectInternal) model;
        return vObj._vmf_getType();
    }

    @Override
    public List<Type> allTypes() {
        // find method handle for getTypes method
        try {
            return (List<Type>) modelAPIClass.getMethod("getTypes").invoke(null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
