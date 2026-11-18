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
package eu.mihosoft.vmf.runtime.core.journal;

import eu.mihosoft.vmf.runtime.core.VObject;

/** Binds a journal's stable identifiers to one version of a VMF model. */
public interface JournalBinding {
    String modelId();

    int modelVersion();

    /** Returns the persistent, unique ID of an object, including in a checkpoint copy. */
    String id(VObject object);

    /** Returns the stable wire name of the object's concrete model type. */
    String type(VObject object);

    /** Creates a fresh mutable object of the bound type with the specified persistent ID. */
    VObject create(String type, String id);

    /** Returns the external value codecs for this model version and its checkpoints. */
    default JournalValueCodecs valueCodecs() {
        return JournalValueCodecs.standard();
    }

    /** Maps a VMF property name to its stable wire key. */
    default String propertyKey(VObject object, String propertyName) {
        return propertyName;
    }

    /** Resolves a wire key back to a VMF property name. */
    default String propertyName(VObject object, String propertyKey) {
        return propertyKey;
    }
}
