# Model validation

Declare property constraints on getters in the VMF model interface. Multiplicity counts values or relationship targets. The other constraints check a present scalar value. The generator rejects malformed declarations and carries constraints from inherited getters into the generated reflection metadata. Constraints from multiple declarations are cumulative.

```java
interface RunWindow {
    @Multiplicity(lower = 1)
    @Length(min = 1, max = 24)
    @Pattern("^[A-Z][A-Z0-9_-]*$")
    String getLabel();

    @Range(min = "0")
    double getStart();

    @Range(min = "0", max = "24", maxInclusive = false)
    double getEnd();

    boolean isScheduled();
    String getSchedule();
}
```

| Constraint | VMF semantics | Draft-07 projection |
| --- | --- | --- |
| `@Multiplicity` | Counts a present scalar, collection members, or map entries. Null slots in legacy model lists count as zero targets. | `required` when a represented property must be supplied, `minItems` and `maxItems` for arrays, `minProperties` and `maxProperties` for string-keyed maps. A scalar with upper bound zero can be omitted or null. |
| `@Range` | Inclusive or exclusive decimal bounds on byte, short, int, long, float, and double, including their boxed types. Use a decimal string for each specified bound to preserve precision. A range rejects non-finite numbers. | `minimum`, `maximum`, `exclusiveMinimum`, and `exclusiveMaximum` as JSON numbers. |
| `@Length` | Inclusive Unicode code-point count for strings. The default maximum is unbounded. | `minLength`, `maxLength` |
| `@Pattern` | Searches for a matching substring. Use anchors to require a whole-string match. Portable patterns support ASCII literals, positive character classes, grouping, alternation, quantifiers, and anchors. Use `portable = false` for Java expressions outside that subset. | `pattern` for portable expressions, including a strict end anchor. Java-only expressions run on the model. |

For a nullable property, range, length, and pattern are evaluated only when it has a value. An empty string counts as a value. Require a nonempty string with multiplicity and length together.

## Validate a model

Call `window.vmf().content().validate()` after building the model or completing a group of edits. The returned `ValidationReport` includes all declared constraints on the window and its contained objects. Its immutable `getIssues()` list contains each issue's code, severity, object, and path. Use `getMultiplicityViolations()` for typed bounds diagnostics.

```java
var report = window.vmf().content().validate();
if (!report.isValid()) {
    report.getIssues().forEach(System.out::println);
}
```

Use a configured `ModelValidator` to add application rules or follow non-containment references. It runs the same declared checks as `content().validate()`.

```java
var validator = ModelValidator.builder()
        .withRule(RunWindow.class, (window, context) -> {
            if (window.getEnd() < window.getStart()) {
                context.report(ValidationIssue.Severity.ERROR, "window.order", "end",
                        "End must be at or after start");
            }
            if (window.isScheduled() && window.getSchedule() == null) {
                context.report(ValidationIssue.Severity.ERROR, "window.schedule.required", "schedule",
                        "A scheduled window needs a schedule");
            }
        })
        .build();

var configuredReport = validator.validate(window);
```

The default scope is the containment tree. Use `withFollowReferences(true)` to include non-containment targets, including inline immutable reference values. A referenced target is visited once by identity. Use `withGraphRule` to run a rule once for the root after the object rules. Only errors make the report invalid. Paths use JSON Pointer syntax with VMF property names and collection indices.

If you used the earlier snapshot API that returned a list, recompile the caller and use `report.getMultiplicityViolations()` to recover those diagnostics. The model generator and runtime must have matching versions to pick up new declarations.

## Validate on import and export a schema

The standard Jackson read path keeps its existing behavior. For a validated import, configure the same validator you use for application validation.

```java
var mode = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(mode)
        .withReferences(true)
        .withModelValidator(validator));
var schema = VMFJsonSchemaGenerator.newInstance(mode)
        .withReferences(true)
        .withTypeConstraint(RunWindow.class, Map.of(
                "if", Map.of(
                        "required", List.of("scheduled"),
                        "properties", Map.of("scheduled", Map.of("const", true))),
                "then", Map.of("required", List.of("schedule"))))
        .generateSchemaAsString(RunWindow.class);
```

The hook runs once on the outermost VMF model after builder defaults, containment, and reference resolution. If the report has errors, the read throws `VMFValidationException` with the report. Wire-level parsing and reference checks can fail earlier.

The generated schema describes the document fields. It uses Jackson names for required fields and accounts for values that the reader can supply by default or reconstruct from relationships. A serialized legacy model list may contain null slots. Its Draft-07 schema can check whether at least one target is present, while the model validator checks the exact bounds on actual targets. Identity, resolved reference types, and key uniqueness are checked by the model or codec. Java-only regexes and arbitrary application rules execute in the model validator. The `withTypeConstraint` call above expresses the schedule rule for the exact type in its wire schema, using serialized field names. The comparison between `start` and `end` runs in the model validator.

## Design references

- EMF's base validator checks feature multiplicity and datatype conformance and can dispatch further invariants to a validation delegate. Eclipse OCL and Epsilon plug into that mechanism. [EMF validator](https://github.com/eclipse-emf/org.eclipse.emf/blob/b940729ba08b2499b08ff505d9f038e2e33cdc73/plugins/org.eclipse.emf.ecore/src/org/eclipse/emf/ecore/util/EObjectValidator.java) · [Eclipse OCL delegates](https://help.eclipse.org/latest/topic/org.eclipse.ocl.doc/help/Delegates.html) · [Epsilon EMF integration](https://eclipse.dev/epsilon/doc/articles/evl-emf-integration/)
- SHACL distinguishes cardinality, value range, and string constraints. Jakarta Validation likewise separates presence from checks such as size and pattern, and offers programmatic validators. [SHACL Core](https://www.w3.org/TR/shacl/#core-components) · [Jakarta Validation](https://jakarta.ee/specifications/bean-validation/3.1/jakarta-validation-spec-3.1)
- Pydantic separates field constraints and programmatic validation hooks and documents regex engine differences. VMF makes the portable regex subset explicit for Draft-07 projection. [Pydantic fields](https://docs.pydantic.dev/latest/concepts/fields/#field-constraints) · [Pydantic regex engine](https://docs.pydantic.dev/latest/api/config/#pydantic.config.ConfigDict.regex_engine) · [Draft-07 validation](https://json-schema.org/draft-07/json-schema-validation)
