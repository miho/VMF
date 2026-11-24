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
package eu.mihosoft.vmf.runtime.core;

/** A property whose current number of values falls outside its declared bounds. */
public final class MultiplicityViolation {
    private final VObject object;
    private final Property property;
    private final int actualCount;

    MultiplicityViolation(VObject object, Property property, int actualCount) {
        this.object = object;
        this.property = property;
        this.actualCount = actualCount;
    }

    /** Model object that declares the affected property. */
    public VObject getObject() { return object; }

    /** Reflective property with its declared type and multiplicity. */
    public Property getProperty() { return property; }

    /** Number of values at validation time. */
    public int getActualCount() { return actualCount; }

    @Override
    public String toString() {
        var bounds = property.getMultiplicity();
        String upper = bounds.getUpper() < 0 ? "*" : String.valueOf(bounds.getUpper());
        return object.vmf().reflect().type().getName() + "." + property.getName()
                + " expects " + bounds.getLower() + ".." + upper + " values, found " + actualCount;
    }
}
