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

import java.util.List;

/** Location and diagnostic sink available during a validation rule callback. */
public final class ValidationContext {
    private final VObject root;
    private final VObject object;
    private final String path;
    private final List<ValidationIssue> issues;

    ValidationContext(VObject root, VObject object, String path, List<ValidationIssue> issues) {
        this.root = root;
        this.object = object;
        this.path = path;
        this.issues = issues;
    }

    public VObject getRoot() { return root; }

    public VObject getObject() { return object; }

    public String getPath() { return path; }

    /** Reports an issue at the current object. */
    public void report(ValidationIssue.Severity severity, String code, String message) {
        issues.add(new ValidationIssue(severity, code, path, message, object, null, null));
    }

    /** Reports an issue at a named property on the current object. */
    public void report(ValidationIssue.Severity severity, String code, String propertyName, String message) {
        Property property = object.vmf().reflect().propertyByName(propertyName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown property " + propertyName));
        issues.add(new ValidationIssue(severity, code, childPath(path, propertyName), message, object, property, null));
    }

    static String childPath(String path, String propertyName) {
        return path + "/" + propertyName.replace("~", "~0").replace("/", "~1");
    }
}
