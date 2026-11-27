package eu.mihosoft.vmf.conformance.ordinarycontainment;

import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.ValidationIssue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class OwningValidationConformance {
    private OwningValidationConformance() {}

    static void verify() {
        var shared = invalidCard();
        var separate = invalidCard();
        separate.setWeight(1);
        var layout = ValidationLayout.newInstance();
        layout.getEntries().addAll(Arrays.asList(null, shared, null, separate));
        layout.getEntries().set(2, shared);
        separate.setWeight(0);
        if (layout.getEntries().get(1) != shared || layout.getEntries().get(2) != shared
                || layout.getEntries().get(3) != separate || shared == separate || !shared.equals(separate)) {
            throw new AssertionError("The list retains both an alias and a distinct equal card");
        }

        var paths = List.of("vmf.multiplicity|/entries/1/labels", "vmf.multiplicity|/entries/1/title",
                "vmf.range|/entries/1/weight", "vmf.multiplicity|/entries/3/labels",
                "vmf.multiplicity|/entries/3/title", "vmf.range|/entries/3/weight");
        var view = layout.asReadOnly();
        var validator = ModelValidator.builder().build();
        for (var report : List.of(layout.vmf().content().validate(), view.vmf().content().validate(),
                validator.validate(layout), validator.validate(view))) {
            var issues = report.getIssues();
            var actual = issues.stream().map(issue -> issue.getCode() + "|" + issue.getPath())
                    .collect(Collectors.toList());
            if (!actual.equals(paths) || issues.stream().anyMatch(issue -> issue.getSeverity() != ValidationIssue.Severity.ERROR)
                    || issues.get(0).getProperty().orElseThrow().getMultiplicity().getLower() != 2
                    || issues.get(0).getProperty().orElseThrow().getMultiplicity().getUpper() != 4
                    || issues.get(0).getMultiplicityViolation().orElseThrow().getActualCount() != 1) {
                throw new AssertionError("Unexpected validation report: " + issues);
            }
        }
        if (validator.validate(view).getIssues().get(0).getObject() != view.getEntries().get(1)) {
            throw new AssertionError("Validation of a view reports the projected source");
        }

        shared.getLabels().add("two");
        shared.setTitle("shared");
        shared.setWeight(2);
        if (!layout.vmf().content().validate().getIssues().stream()
                .map(issue -> issue.getCode() + "|" + issue.getPath()).collect(Collectors.toList())
                .equals(paths.subList(3, 6))) {
            throw new AssertionError("Fixing the shared card leaves only the distinct card's diagnostics");
        }
    }

    private static ValidationCard invalidCard() {
        var card = ValidationCard.newInstance();
        card.getLabels().add("one");
        card.setWeight(0);
        return card;
    }
}
