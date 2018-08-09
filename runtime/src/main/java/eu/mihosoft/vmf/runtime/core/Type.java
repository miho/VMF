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
package eu.mihosoft.vmf.runtime.core;

import java.util.Objects;

public final class Type {

    private final boolean modelType;
    private final boolean listType;
    private final String name;

    private Type(boolean modelType, boolean listType, String name) {
        this.modelType = modelType;
        this.listType = listType;
        this.name = name;
    }

    static Type newInstance(boolean modelType, boolean listType, String name) {
        return new Type(modelType, listType, name);
    }

    public String getName() {
        return this.name;
    }

    public boolean isModelType() {
        return this.modelType;
    }

    public boolean isListType() {
        return listType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Type type = (Type) o;
        return modelType == type.modelType && listType == type.listType &&
                Objects.equals(name, type.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelType, listType, name);
    }

    @Override
    public String toString() {
        return "[ name=" + name + ", modelType=" + modelType + ", listType=" + listType + " ]";
    }
}
