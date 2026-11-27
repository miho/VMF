/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 */
package eu.mihosoft.vmf.jackson.test.defaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;

interface NullableScalarDefaults {
    @DefaultValue("\"draft\"")
    @Annotation(key = "vmf:jackson:rename", value = "display_label")
    String getLabel();

    @DefaultValue("42") Integer getCount();
    @DefaultValue("'X'") Character getSymbol();
    @DefaultValue("null") String getExplicitNull();
    String getUnnamed();

    @Contains NullableScalarChild getChild();
    NullableScalarChild getRelated();
}

interface NullableScalarChild {
    String getName();
}
