/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
package eu.mihosoft.vmftest.readonlyexternallist.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.ExternalType;

@ExternalType(pkgName = "java.lang")
@Annotation(key = "vmf:csharp:external-type", value = "System.Text.StringBuilder")
@Annotation(key = "vmf:typescript:external-type", value = "StringBuilder")
@Annotation(key = "vmf:typescript:external-module", value = "@vmftest/hosts")
interface StringBuilder {
}

/**
 * Collection properties with external element types next to one with model
 * elements, for the read-only views (miho/VMF#92).
 */
interface Sample {

    /**
     * External element type that is immutable itself.
     */
    String[] getLabels();

    /**
     * External element type that is mutable: the read-only view hands out the
     * same instances (VMF does not freeze external values).
     */
    java.lang.StringBuilder[] getBuffers();

    /**
     * Model elements (exposed as an unmodifiable mapped view before #92).
     */
    @Contains
    Entry[] getEntries();
}

interface Entry {
    String getName();
}
