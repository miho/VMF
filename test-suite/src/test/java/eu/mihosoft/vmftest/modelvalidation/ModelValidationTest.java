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
package eu.mihosoft.vmftest.modelvalidation;

import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.ValidationIssue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static eu.mihosoft.vmf.runtime.core.ValidationIssue.Severity.ERROR;
import static eu.mihosoft.vmf.runtime.core.ValidationIssue.Severity.WARNING;
import static org.junit.Assert.*;

public class ModelValidationTest {
    @Test public void checksDeclaredBoundsAndValuesOnRootAndChildren() {
        var root = ValidationRoot.newInstance();
        var invalid = Segment.newInstance();
        invalid.setStart(10.0);
        invalid.setCode("1");
        root.getSegments().add(validSegment());
        root.getSegments().add(null);
        root.getSegments().add(invalid);

        var report = root.vmf().content().validate();
        assertFalse(report.isValid());
        assertEquals("/name", report.getIssues().get(0).getPath());
        var bounded = report.getIssues().get(0).getMultiplicityViolation().orElseThrow();
        assertSame(root, bounded.getObject());
        assertEquals("name", bounded.getProperty().getName());
        assertEquals(0, bounded.getActualCount());
        assertEquals(4, report.getIssues().size());
        assertEquals(2, report.getIssues().stream().filter(i -> i.getPath().equals("/segments/2/code")).count());
        assertTrue(report.getIssues().stream().anyMatch(i -> i.getPath().equals("/segments/2/start")));
        assertEquals(List.of(bounded), report.getMultiplicityViolations());
        assertThrows(UnsupportedOperationException.class, () -> report.getIssues().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.getMultiplicityViolations().clear());

        var readOnly = root.asReadOnly();
        var readOnlyReport = readOnly.vmf().content().validate();
        var configuredReport = ModelValidator.builder().build().validate(root);
        assertEquals(diagnostics(report.getIssues()), diagnostics(readOnlyReport.getIssues()));
        assertEquals(diagnostics(report.getIssues()), diagnostics(configuredReport.getIssues()));
        assertSame(readOnly, readOnlyReport.getMultiplicityViolations().get(0).getObject());
        assertSame(readOnly.getSegments().get(2), readOnlyReport.getIssues().get(1).getObject());

        root.setName("root");
        invalid.setCode("valid");
        invalid.setStart(0.0);
        assertTrue(root.vmf().content().validate().isValid());
    }

    @Test public void dispatchesRulesInOrderAndPreservesReadOnlyViews() {
        var root = ValidationRoot.newInstance();
        root.setName("root");
        var segment = validSegment();
        segment.setStart(6.0);
        segment.setEnd(4.0);
        root.getSegments().add(segment);

        List<String> invocations = new ArrayList<>();
        var validator = ModelValidator.builder()
                .withRule(Window.class, (window, context) -> {
                    assertSame(segment, window);
                    assertSame(root, context.getRoot());
                    invocations.add(context.getPath());
                    if (window.getStart() > window.getEnd()) {
                        context.report(ERROR, "window.order", "end", "End precedes start");
                    }
                })
                .withRule(ReadOnlyWindow.class, (window, context) -> {
                    assertTrue(context.getObject() instanceof ReadOnlySegment);
                    context.report(WARNING, "window.read-only", "Read-only check");
                })
                .withGraphRule((model, context) -> {
                    assertSame(context.getRoot(), model);
                    invocations.add("root");
                    context.report(WARNING, "root.review", "Review the complete graph");
                })
                .build();

        var mutableReport = validator.validate(root);
        assertFalse(mutableReport.isValid());
        assertEquals(List.of("/segments/0", "root"), invocations);
        assertEquals(List.of("/segments/0/end", ""), paths(mutableReport.getIssues()));
        assertEquals("end", mutableReport.getIssues().get(0).getProperty().orElseThrow().getName());
        assertTrue(root.vmf().content().validate().getIssues().isEmpty());
        assertTrue(root.asReadOnly().vmf().content().validate().getIssues().isEmpty());
        assertEquals(List.of("/segments/0", "root"), invocations);

        var readOnlyReport = validator.validate(root.asReadOnly());
        assertTrue(readOnlyReport.isValid());
        assertEquals(List.of("/segments/0", ""), paths(readOnlyReport.getIssues()));
        assertEquals("window.read-only", readOnlyReport.getIssues().get(0).getCode());
    }

    @Test public void followsReferencesOnceWithoutChangingContainmentPaths() {
        var root = ValidationRoot.newInstance();
        root.setName("root");
        var contained = validSegment();
        var remote = Segment.newInstance();
        root.getSegments().add(contained);
        root.setRemoteSegment(remote);
        contained.setNext(remote);
        remote.setNext(contained);

        assertTrue(root.vmf().content().validate().isValid());
        var traversed = ModelValidator.builder().withFollowReferences(true).build();
        var report = traversed.validate(root);
        assertFalse(report.isValid());
        assertEquals(1, report.getIssues().size());
        assertSame(remote, report.getIssues().get(0).getObject());
        assertEquals("/remoteSegment/code", report.getIssues().get(0).getPath());

        contained.setCode("1");
        assertEquals(List.of("/segments/0/code", "/segments/0/code"),
                paths(traversed.validate(root).getIssues()).subList(0, 2));

        root.setName(null);
        remote.setCode("valid");
        contained.setCode("valid");
        assertTrue(traversed.validate(contained).isValid());
    }

    private static Segment validSegment() {
        return Segment.newBuilder().withCode("valid").withStart(0.0).withEnd(1.0).build();
    }

    private static List<String> paths(List<ValidationIssue> issues) {
        return issues.stream().map(ValidationIssue::getPath).collect(Collectors.toList());
    }

    private static List<String> diagnostics(List<ValidationIssue> issues) {
        return issues.stream().map(issue -> issue.getPath() + " " + issue.getCode()).collect(Collectors.toList());
    }
}
