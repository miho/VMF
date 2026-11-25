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

import java.util.Objects;
import java.util.Optional;

/** A diagnostic at a model object or property location. */
public final class ValidationIssue {
    public enum Severity { ERROR, WARNING, INFO }

    private final Severity severity;
    private final String code;
    private final String path;
    private final String message;
    private final VObject object;
    private final Property property;
    private final MultiplicityViolation multiplicityViolation;

    ValidationIssue(Severity severity, String code, String path, String message,
                    VObject object, Property property, MultiplicityViolation multiplicityViolation) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = Objects.requireNonNull(code, "code");
        if (code.isBlank()) throw new IllegalArgumentException("Issue code must not be blank");
        this.path = path;
        this.message = Objects.requireNonNull(message, "message");
        this.object = object;
        this.property = property;
        this.multiplicityViolation = multiplicityViolation;
    }

    public Severity getSeverity() { return severity; }

    /** Stable identifier for this rule or kind of issue. */
    public String getCode() { return code; }

    /**
     * Model property names and collection indices in JSON Pointer syntax.
     * The empty string identifies the root. Jackson field aliases are independent.
     */
    public String getPath() { return path; }

    public String getMessage() { return message; }

    public VObject getObject() { return object; }

    public Optional<Property> getProperty() { return Optional.ofNullable(property); }

    public Optional<MultiplicityViolation> getMultiplicityViolation() {
        return Optional.ofNullable(multiplicityViolation);
    }

    @Override
    public String toString() {
        String location = path.isEmpty() ? "root" : path;
        return severity + " " + code + " at " + location + ": " + message;
    }
}
