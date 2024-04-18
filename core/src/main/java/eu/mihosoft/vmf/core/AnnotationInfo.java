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
package eu.mihosoft.vmf.core;

import java.util.Objects;

/**
 * An annotation info stores the key and value of a custom model annotation.
 */
@Deprecated
public final class AnnotationInfo {

    private String key;
    private String value;

    /**
     * Creates a new annotation info.
     * @param key annotation key
     * @param value annotation value
     */
    public AnnotationInfo(String key, String value) {
        this.key = key;
        this.value = value;
    }

    /**
     * 
     * @return annotation key
     */
    public String getKey() {
        return key;
    }

    /**
     * Sets the annotation key.
     * @param key string to set
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * 
     * @return annotation value
     */
    public String getValue() {
        return value;
    }

    /**
     * Sets the annotation value.
     * @param value string to set
     */
    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AnnotationInfo that = (AnnotationInfo) o;
        return Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }


}
