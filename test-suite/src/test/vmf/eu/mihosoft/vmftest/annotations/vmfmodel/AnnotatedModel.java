/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmftest.annotations.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;

@Annotation(key = "key 1", value = "my value 1")
@Annotation(key = "key 2", value = "my value 2")
interface AnnotatedModel {

    @Annotation(key="prop key 1", value = "my prop value 1")
    @Annotation(key="prop key 2", value = "my prop value 2")
    String getName();
}


@Annotation(key = "key 1", value = "my value 1")
@Annotation(key = "key 2", value = "my value 2")
@Annotation(key = "key 2", value = "my value 3")
interface MultipleAnnotationsPerKey {

}


@Annotation(key = "key 1", value = "my parent value 1")
@Annotation(key = "key 2", value = "my parent value 2")
interface AnnotationInheritance1Parent {

}

@Annotation(key = "key 1", value = "my child value 1")
@Annotation(key = "key 2", value = "my child value 2")
interface AnnotationInheritance1Child {

}


interface AnnotationInheritance2Parent {
    @Annotation(key = "key 1", value = "my parent value 1")
    @Annotation(key = "key 2", value = "my parent value 2")
    String getName();
}


interface AnnotationInheritance2Child {
    @Annotation(key = "key 1", value = "my child value 1")
    @Annotation(key = "key 2", value = "my child value 2")
    String getName();
}
