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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an interface as being external. That is, it is not included in the code generator. Only its fully qualified
 * name is used for method signatures and properties. The following sample model adds the external type {@code Vector3d}
 * to the model without the necessity to add it to the classpath of the code generator.
 *
 * <pre><code>
 * package eu.mihosoft.vmf.tutorial15.vmfmodel;
 *
 * import eu.mihosoft.vmf.core.*;
 *
 * {@literal @}ExternalType(pkgName="eu.mihosoft.vvecmath")
 * interface Vector3d {}
 *
 *
 * interface MyModel {
 *     Vector3d getLocation();
 * }
 * </code></pre>
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-15/README.md">Tutorial on External Types</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface ExternalType {
    String pkgName();
}