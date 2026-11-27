package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultBase;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.InstrumentProbe;
import eu.mihosoft.vmf.conformance.values.InstrumentRack;
import eu.mihosoft.vmf.conformance.values.NonzeroPatternedLabel;
import eu.mihosoft.vmf.conformance.values.ReadOnlyScalarBounds;
import eu.mihosoft.vmf.conformance.values.ScalarBatch;
import eu.mihosoft.vmf.conformance.values.ScalarBounds;
import eu.mihosoft.vmf.conformance.values.ScalarEntry;
import eu.mihosoft.vmf.conformance.values.ScalarSample;
import eu.mihosoft.vmf.conformance.values.SignalMatrix;
import eu.mihosoft.vmf.conformance.values.SignalNode;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFValidationException;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.ValidationIssue;
import eu.mihosoft.vmf.runtime.core.ValidationReport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static eu.mihosoft.vmf.runtime.core.ValidationIssue.Severity.ERROR;
import static eu.mihosoft.vmf.runtime.core.ValidationIssue.Severity.INFO;
import static eu.mihosoft.vmf.runtime.core.ValidationIssue.Severity.WARNING;

final class ConfiguredValidationConformance {
    private ConfiguredValidationConformance() {}

    public static void main(String[] args) throws Exception {
        checkRuleMatchingAndDiagnosticMetadata();
        checkContainmentThenReferenceOrder();
        checkImmutableIdentity();
        checkValidatedImport();
        checkNumericWidths();
        checkNativePatternBindings();
        checkSingularMultiplicity();
        checkReferenceMultiplicity();
        checkBuilderRelationshipMultiplicity();
        System.out.println("Java verified configured validation, diagnostic paths, and validation after reference hydration.");
    }

    private static void checkReferenceMultiplicity() throws Exception {
        var a = SignalNode.newBuilder().withName("a").build();
        var b = SignalNode.newBuilder().withName("b").build();
        var c = SignalNode.newBuilder().withName("c").build();
        var disabled = SignalNode.newInstance();
        var matrix = SignalMatrix.newBuilder().withNodes(a, b, c).build();
        var validator = ModelValidator.builder().build();
        check(summaries(validator.validate(matrix)).equals(List.of("ERROR|vmf.multiplicity|/selected")), "required reference set");
        matrix.getSelected().addAll(List.of(a, b, c));
        matrix.setDisabledLink(b);
        matrix.setDisabledOwned(disabled);
        check(disabled.getDisabledOwner() == matrix, "disabled inverse still identifies the owning field");
        var report = validator.validate(matrix);
        check(paths(report).equals(List.of("/disabledLink", "/disabledOwned", "/selected", "/disabledOwned/disabledOwner")),
                "invalid relationship bounds report their logical paths: " + paths(report));
        check(report.getIssues().stream().map(issue -> issue.getMultiplicityViolation().orElseThrow().getActualCount())
                .collect(Collectors.toList()).equals(List.of(1, 1, 3, 1)), "invalid relationship counts");
        matrix.getSelected().remove(c);
        matrix.setDisabledLink(null);
        matrix.setDisabledOwned(null);
        var io = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(true).withModelValidator(validator));
        var restored = io.readValue(io.writeValueAsString(matrix), SignalMatrix.class);
        check(restored.getSelected().size() == 2 && restored.getSelected().contains(restored.getNodes().get(1)),
                "hydration resolves reference membership before validation");
    }

    private static void checkBuilderRelationshipMultiplicity() {
        var a = SignalNode.newBuilder().withName("a").build();
        var b = SignalNode.newBuilder().withName("b").build();
        var c = SignalNode.newBuilder().withName("c").build();
        var disabled = SignalNode.newBuilder().withName("disabled").build();
        var source = SignalMatrix.newBuilder().withSelected(a, b, c).withDisabledLink(b)
                .withDisabledOwned(disabled).build();
        var builder = SignalMatrix.newBuilder().applyFrom(source);

        var oldSelected = SignalNode.newBuilder().withName("old selected").build();
        var oldOwned = SignalNode.newBuilder().withName("old owned").build();
        var target = SignalMatrix.newBuilder().withSelected(oldSelected).withDisabledOwned(oldOwned).build();
        var selected = target.getSelected();
        builder.applyTo(target);
        check(selected == target.getSelected() && selected.size() == 4 && selected.contains(oldSelected),
                "Builder append retains identity and over-limit membership for validation");
        check(target.getDisabledOwned() == oldOwned && disabled.getDisabledOwner() == source
                && target.getDisabledLink() == b, "Application keeps singular owned storage and updates references");
        var validator = ModelValidator.builder().build();
        check(validator.validate(target).getIssues().stream().anyMatch(issue -> issue.getPath().equals("/selected")
                && issue.getMultiplicityViolation().orElseThrow().getActualCount() == 4),
                "Append reports rather than rejects over-limit membership");
        source.getSelected().clear();
        builder.appendCollections(false).applyTo(target);
        check(selected.size() == 3 && List.of(a, b, c).stream().allMatch(selected::contains) && target.getDisabledOwned() == oldOwned,
                "Replacement uses the staged identity snapshot");

        var built = builder.build();
        check(built.getDisabledOwned() == disabled && disabled.getDisabledOwner() == built
                && source.getDisabledOwned() == null && built.getDisabledLink() == b,
                "Build transfers the staged owning value and its inverse");
        check(paths(validator.validate(built)).equals(
                List.of("/disabledLink", "/disabledOwned", "/selected", "/disabledOwned/disabledOwner")),
                "Build leaves all multiplicity violations to the validator");
    }

    private static void checkSingularMultiplicity() throws Exception {
        var validator = ModelValidator.builder().build();
        var probe = InstrumentProbe.newBuilder().withReserved("forbidden").build();
        var detached = validator.validate(probe);
        check(summaries(detached).equals(List.of("ERROR|vmf.multiplicity|/rack", "ERROR|vmf.multiplicity|/reserved")),
                "detached mandatory container and disabled scalar: " + summaries(detached));
        check(detached.getIssues().stream().map(issue -> issue.getMultiplicityViolation().orElseThrow().getActualCount())
                .collect(Collectors.toList()).equals(List.of(0, 1)), "singular counts");
        var rack = InstrumentRack.newBuilder().withProbes(probe).build();
        check(probe.getRack() == rack && summaries(validator.validate(rack)).equals(List.of("ERROR|vmf.multiplicity|/probes/0/reserved")),
                "owning list establishes required inverse");
        probe.setReserved(null);
        check(validator.validate(rack.asReadOnly()).isValid(), "null scalar satisfies disabled slot");

        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withModelValidator(validator));
        var imported = mapper.readValue("{\"probes\":[{}]}", InstrumentRack.class);
        check(imported.getProbes().get(0).getRack() == imported, "hydrate inverse before validation");
        try {
            mapper.readValue("{\"probes\":[{\"reserved_slot\":\"x\"}]}", InstrumentRack.class);
            throw new AssertionError("Import accepted a populated disabled property");
        } catch (VMFValidationException rejected) {
            check(summaries(rejected.getReport()).equals(List.of("ERROR|vmf.multiplicity|/probes/0/reserved")),
                    "wire alias reports the logical path");
        }
    }

    private static void checkNativePatternBindings() throws Exception {
        var validator = ModelValidator.builder().build();
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withModelValidator(validator));
        var imported = mapper.readValue("{\"tracking_label\":\"NP90\"}", NonzeroPatternedLabel.class);
        check(imported.getLabel().equals("NP90") && validator.validate(imported.asReadOnly()).isValid(), "native pattern accepted");
        check(validator.validate(NonzeroPatternedLabel.newInstance()).isValid(), "native pattern ignores null");
        for (String input : List.of("AI12", "BO09", "ab12", "AB00")) {
            var report = validator.validate(NonzeroPatternedLabel.newBuilder().withLabel(input).build());
            check(summaries(report).equals(List.of("ERROR|vmf.pattern|/label")), "invalid label " + input + ": " + summaries(report));
        }
        for (String input : List.of("IO00", "AB12\n")) {
            var report = validator.validate(NonzeroPatternedLabel.newBuilder().withLabel(input).build());
            check(summaries(report).equals(List.of("ERROR|vmf.pattern|/label", "ERROR|vmf.pattern|/label")), "both inherited patterns");
        }
        try {
            mapper.readValue("{\"tracking_label\":\"AB00\"}", NonzeroPatternedLabel.class);
            throw new AssertionError("Import accepted an invalid native pattern");
        } catch (VMFValidationException rejected) {
            check(summaries(rejected.getReport()).equals(List.of("ERROR|vmf.pattern|/label")), "invalid native pattern import");
        }
    }

    private static void checkRuleMatchingAndDiagnosticMetadata() {
        var batch = ScalarBatch.newBuilder().withTitle("B").build();
        var entry = ScalarEntry.newBuilder().withSequence(1L).withStatusLabel("x").build();
        batch.getEntries().add(entry);
        var invocations = new ArrayList<String>();
        var builder = ModelValidator.builder()
                .withRule(ScalarBounds.class, (model, context) -> {
                    check(model == entry && context.getObject() == entry && context.getRoot() == batch,
                            "mutable base type rule sees derived identity");
                    invocations.add("mutable base " + context.getPath());
                    context.report(WARNING, "sequence.review", "sequence", "Review sequence");
                })
                .withRule(ScalarEntry.class, (model, context) -> {
                    invocations.add("mutable subtype " + context.getPath());
                    context.report(ERROR, "status.review", "statusLabel", "Review status");
                })
                .withRule(ReadOnlyScalarBounds.class, (model, context) -> {
                    invocations.add("read-only base " + context.getPath());
                    context.report(INFO, "read-only.review", "Review read-only entry");
                })
                .withGraphRule((root, context) -> {
                    check(root == context.getRoot() && root == context.getObject() && context.getPath().isEmpty(),
                            "root graph context");
                    invocations.add("graph");
                    context.report(WARNING, "graph.review", "Review model");
                });
        var validator = builder.build();
        builder.withGraphRule((model, context) -> { throw new AssertionError("builder changed existing validator"); });

        var report = validator.validate(batch);
        var issues = report.getIssues();
        check(!report.isValid(), "declared and configured errors affect validity");
        check(invocations.equals(List.of("mutable base /entries/0", "mutable subtype /entries/0", "graph")),
                "assignable rules run in registration order");
        check(summaries(report).equals(List.of(
                "ERROR|vmf.range|/entries/0/sequence",
                "ERROR|vmf.length|/entries/0/statusLabel",
                "ERROR|vmf.pattern|/entries/0/statusLabel",
                "WARNING|sequence.review|/entries/0/sequence",
                "ERROR|status.review|/entries/0/statusLabel",
                "WARNING|graph.review|")), "declared property checks precede application rules: " + summaries(report));
        check(issues.get(0).getObject() == entry && issues.get(0).getProperty().orElseThrow().getName().equals("sequence")
                && issues.get(4).getProperty().orElseThrow().getName().equals("statusLabel")
                && issues.get(5).getProperty().isEmpty(), "issues retain model and logical property");
        reject(UnsupportedOperationException.class, issues::clear);

        invocations.clear();
        entry.setSequence(9_007_199_254_740_993L);
        entry.setStatusLabel("A-12");
        var view = batch.asReadOnly();
        var readOnly = validator.validate(view);
        check(readOnly.isValid(), "warning and info do not change validity");
        check(invocations.equals(List.of("read-only base /entries/0", "graph")), "read-only dispatch is view-aware");
        check(summaries(readOnly).equals(List.of("INFO|read-only.review|/entries/0", "WARNING|graph.review|"))
                && readOnly.getIssues().get(0).getObject() == view.getEntries().get(0), "read-only issue identity");

        var missing = ModelValidator.builder().withRule(ScalarEntry.class, (model, context) ->
                context.report(ERROR, "wrong", "status_label", "Wire alias is independent")).build();
        reject(IllegalArgumentException.class, () -> missing.validate(batch));
    }

    private static void checkContainmentThenReferenceOrder() {
        var root = ArchiveRoot.newInstance();
        var head = node("head");
        var left = node("left");
        var right = node("right");
        var remote = node("remote");
        var deep = node("deep");
        var far = node("far");
        root.setHead(head);
        head.getChildren().add(left);
        head.setInset(right);
        root.getReview().add(null);
        root.getReview().add(left);
        root.getReview().add(remote);
        root.getReview().add(remote);
        head.setAnchor(remote);
        remote.getChildren().add(deep);
        remote.getTrail().add(far);
        remote.setAnchor(head);

        var containmentOnly = ModelValidator.builder().withRule(VObject.class, (object, context) ->
                context.report(INFO, "visit", "Visited model")).build();
        check(paths(containmentOnly.validate(root)).equals(List.of("", "/head", "/head/children/0", "/head/inset")),
                "defaults to containment");
        var recursive = ModelValidator.builder()
                .withFollowReferences(true)
                .withRule(VObject.class, (object, context) -> context.report(INFO, "visit", "Visited model"))
                .build();
        var report = recursive.validate(root);
        check(paths(report).equals(List.of("", "/head", "/head/children/0", "/head/inset",
                "/review/2", "/review/2/children/0", "/review/2/trail/0")), "reference queue order: " + paths(report));
        check(report.getIssues().get(4).getObject() == remote, "earliest location of reference identity");
        var view = root.asReadOnly();
        var readOnly = recursive.validate(view);
        check(paths(readOnly).equals(paths(report)) && readOnly.getIssues().get(4).getObject() == view.getReview().get(2),
                "read-only reference traversal retains projected identities");
        check(paths(recursive.validate(left)).equals(List.of("")), "inverse parent is never followed");
    }

    private static void checkImmutableIdentity() {
        var first = ValueLabel.newBuilder().withText("same").build();
        var second = ValueLabel.newBuilder().withText("same").build();
        var root = ValueSelection.newBuilder().withCurrent(first).build();
        root.getSelected().add(first);
        root.getSelected().add(second);
        var seen = new ArrayList<ValueLabel>();
        var validator = ModelValidator.builder().withFollowReferences(true)
                .withRule(ValueLabel.class, (label, context) -> seen.add(label))
                .build();
        check(first.equals(second) && first != second, "immutable values have equal content and different identity");
        check(validator.validate(root).isValid() && seen.size() == 2 && seen.get(0) == first && seen.get(1) == second,
                "immutable aliases also deduplicate by identity");
    }

    private static void checkValidatedImport() throws Exception {
        var called = new AtomicInteger();
        var validator = ModelValidator.builder().withGraphRule((model, context) -> {
            called.incrementAndGet();
            var archive = (ArchiveRoot) model;
            var child = archive.getHead().getChildren().get(0);
            check(archive.getHead().getAnchor() == child && archive.getReview().get(0) == child,
                    "validator sees fully hydrated forward references");
            context.report(WARNING, "archive.review", "Review archive");
        }).build();
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(true).withModelValidator(validator));
        String json = "{\"@vmf-id\":\"archive\",\"review\":[\"late\",null,\"late\"],\"head\":"
                + "{\"@vmf-id\":\"head\",\"name\":\"head\",\"children\":[{\"@vmf-id\":\"late\",\"name\":\"late\"}],\"anchor\":\"late\"}}";
        var hydrated = mapper.readValue(json, ArchiveRoot.class);
        check(called.get() == 1 && hydrated.getReview().get(0) == hydrated.getHead().getAnchor(), "valid import ran once");
        try {
            mapper.readValue(json.replace("\"late\",null,\"late\"", "\"late\",null"), ArchiveRoot.class);
            throw new AssertionError("Import accepted invalid multiplicity");
        } catch (VMFValidationException invalid) {
            var issue = invalid.getReport().getIssues().get(0);
            var multiplicity = issue.getMultiplicityViolation().orElseThrow();
            check(issue.getPath().equals("/review") && issue.getCode().equals("vmf.multiplicity")
                    && multiplicity.getActualCount() == 1 && multiplicity.getObject() == issue.getObject()
                    && multiplicity.getProperty().getName().equals("review"), "invalid import retains typed diagnostic");
            check(called.get() == 2, "invalid import ran once");
        }
        try {
            mapper.readValue(json.replace("\"anchor\":\"late\"", "\"anchor\":\"missing\""), ArchiveRoot.class);
            throw new AssertionError("Import accepted unresolved reference");
        } catch (JsonMappingException unresolved) {
            check(!(unresolved instanceof VMFValidationException) && called.get() == 2,
                    "unresolved reference fails before validation");
        }
        var defaultsCalled = new AtomicInteger();
        var defaultsRule = ModelValidator.builder().withRule(DefaultBase.class, (model, context) -> {
            defaultsCalled.incrementAndGet();
            check(model.getName().equals("idle\nready") && model.getLimit() == 7, "rules see inherited defaults after import");
        }).build();
        for (boolean references : List.of(true, false)) {
            var defaultsMapper = new ObjectMapper().registerModule(
                    VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                            .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT).withModelValidator(defaultsRule));
            defaultsMapper.readValue("{}", DefaultSettings.class);
        }
        check(defaultsCalled.get() == 2, "default checks run once in each JSON profile");
    }

    private static ArchiveNode node(String name) { return ArchiveNode.newBuilder().withName(name).build(); }

    private static void checkNumericWidths() throws Exception {
        var sample = ScalarSample.newBuilder().withIntValue(Integer.MIN_VALUE).withOptionalInt(Integer.MAX_VALUE)
                .withLongValue(Long.MIN_VALUE).withOptionalLong(Long.MAX_VALUE)
                .withFloatValue(0.125f).withDoubleValue(0.25).withOptionalFloat(0.0625f).withOptionalDouble(0.03125)
                .withTinyFloat(Float.MIN_VALUE).withTinyDouble(Double.MIN_VALUE).withSignedZero(-0.0)
                .withNegativeInfinity(Double.NEGATIVE_INFINITY).withCharValue('x').build();
        var collections = CollectionProfile.newBuilder().withCounts(Integer.MAX_VALUE).withLevels(1.0).build();
        collections.getReadings().add(0.015625);
        collections.getLimits().put("limit", 1L);
        var bounds = ScalarBounds.newBuilder().withSequence(9_007_199_254_740_993L).build();
        for (boolean references : List.of(false, true)) {
            for (var mapper : List.of(new ObjectMapper(), new XmlMapper(), new YAMLMapper())) {
                var calls = new AtomicInteger();
                var validator = ModelValidator.builder().withGraphRule((model, context) -> calls.incrementAndGet()).build();
                mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT).withModelValidator(validator));

                String scalars = mapper.writeValueAsString(sample);
                var restored = mapper.readValue(scalars, ScalarSample.class);
                check(restored.getIntValue() == Integer.MIN_VALUE && restored.getOptionalInt() == Integer.MAX_VALUE
                        && restored.getLongValue() == Long.MIN_VALUE && restored.getOptionalLong() == Long.MAX_VALUE,
                        "signed integer boundaries");
                check(restored.getFloatValue() == 0.125f && restored.getDoubleValue() == 0.25
                        && restored.getOptionalFloat() == 0.0625f && restored.getOptionalDouble() == 0.03125
                        && restored.getTinyFloat() == Float.MIN_VALUE && restored.getTinyDouble() == Double.MIN_VALUE
                        && Double.doubleToRawLongBits(restored.getSignedZero()) == Long.MIN_VALUE
                        && restored.getNegativeInfinity() == Double.NEGATIVE_INFINITY, "signed decimal boundaries and specials");
                rejectNumberOutsideWidth(mapper, scalars, ScalarSample.class, "-2147483648", "-4294967296", "int");
                rejectNumberOutsideWidth(mapper, scalars, ScalarSample.class, "2147483647", "4294967296", "int");
                rejectNumberOutsideWidth(mapper, scalars, ScalarSample.class, "-9223372036854775808", "-18446744073709551616", "long");
                rejectNumberOutsideWidth(mapper, scalars, ScalarSample.class, "9223372036854775807", "18446744073709551616", "long");
                rejectNumberOutsideWidth(mapper, scalars, ScalarSample.class, "0.125", "1e40", "float");
                rejectNumberOutsideWidth(mapper, scalars, ScalarSample.class, "0.25", "1e400", "double");
                rejectNumberOutsideWidth(mapper, scalars, ScalarSample.class, "0.0625", "-1e-50", "float");
                rejectNumberOutsideWidth(mapper, scalars, ScalarSample.class, "0.03125", "-1e-400", "double");

                String counts = mapper.writeValueAsString(collections);
                var members = mapper.readValue(counts, CollectionProfile.class);
                check(members.getCounts().equals(List.of(Integer.MAX_VALUE))
                        && members.getReadings().equals(List.of(0.015625)), "integer and decimal collection members");
                rejectNumberOutsideWidth(mapper, counts, CollectionProfile.class, "2147483647", "4294967296", "int");
                rejectNumberOutsideWidth(mapper, counts, CollectionProfile.class, "0.015625", "1e-400", "double");

                String range = mapper.writeValueAsString(bounds);
                check(mapper.readValue(range, ScalarBounds.class).getSequence() == 9_007_199_254_740_993L,
                        "valid constrained long");
                rejectNumberOutsideWidth(mapper, range, ScalarBounds.class, "9007199254740993", "18455751272964292609", "long");
                check(calls.get() == 3, "unrepresentable numbers are rejected before configured validation");
            }
        }
    }

    private static <T> void rejectNumberOutsideWidth(ObjectMapper mapper, String original, Class<T> type,
                                                      String boundary, String outside, String scalarType) throws Exception {
        String modified = original.replace(boundary, outside);
        check(!modified.equals(original), "boundary was present in writer output");
        try {
            mapper.readValue(modified, type);
            throw new AssertionError("Import accepted unrepresentable number " + outside);
        } catch (JsonMappingException error) {
            Throwable cause = error;
            while (cause != null && !String.valueOf(cause.getMessage()).contains("outside the range of " + scalarType)) {
                cause = cause.getCause();
            }
            check(cause != null, "expected integer-width failure: " + error);
        }
    }

    private static List<String> paths(ValidationReport report) {
        return report.getIssues().stream().map(ValidationIssue::getPath).collect(Collectors.toList());
    }

    private static List<String> summaries(ValidationReport report) {
        return report.getIssues().stream().map(issue -> issue.getSeverity() + "|" + issue.getCode() + "|" + issue.getPath())
                .collect(Collectors.toList());
    }

    private static void reject(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            if (type.isInstance(error)) return;
            throw error;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
