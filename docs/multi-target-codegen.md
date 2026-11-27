# Multi-target VMF code generation

This design covers a Java-hosted generator for native VMF models in C#, C++, TypeScript, Rust, and Python. C# is the first new backend. The existing Java implementation supplies the initial reference behavior.

## Generator boundary

Start after [model resolution](../core/src/main/java/eu/mihosoft/vmf/core/CodeGenerator.java#L141). The annotated Java interfaces continue to supply the declarations. Project the resolved model into a small internal, read-only representation that records model facts independently of Java rendering.

- Record declaration and property identities, inheritance, scalar types, and resolved type references. Represent generic parameter identity, bounds, and ordered arguments explicitly.
- Record value or reference equality, containment, opposite ends, multiplicity, ordering, uniqueness, mutability, and presence separately from target collection and property types.
- Carry declarative constraints and source locations. Treat Java delegates, executable default expressions, and external types as inputs that need an explicit target binding.
- Keep target namespaces, naming and escaping, native type mappings, and file layout in each backend. Reuse the existing [resource output interface](../core/src/main/java/eu/mihosoft/vmf/core/io/ResourceSet.java) where it fits.

Each backend checks its supported features against the model before emitting files. Diagnostics identify the target, declaration or property, and missing capability or binding. Each backend can lower the same model facts to its own native API and runtime. Keep the shared representation internal until a second non-Java backend has tested its assumptions. Delay any serialized schema or public backend API until there is a consumer for it.

## Target semantics

Semantic parity means the same model operations produce the same observable graph and validation results. The target language determines how code calls those operations.

| Target | Design pressure |
| --- | --- |
| C# | Native interfaces and properties, identity-aware relationship collections, explicit presence semantics, and .NET validation. |
| C++ | Explicit graph lifetime and safe references across containment changes. Evaluate graph-owned handles with the first C++ runtime fixture. |
| Rust | Explicit mutation and ownership for a graph with inverse and cyclic cross-references. Evaluate typed handles or arena ownership in the Rust backend. |
| TypeScript | Runtime checks for erased types and a lossless representation for 64-bit integers. Specify the wire encoding if these values cross a JSON boundary. |
| Python | Runtime enforcement of declared types, numeric ranges, and managed relationship changes. |

[VMF collection behavior](collection-properties.md) and [model validation](model-validation.md) define the first compatibility cases. [TypeScript type erasure](https://www.typescriptlang.org/docs/handbook/2/basic-types.html#erased-types), [ECMAScript safe integers](https://tc39.es/ecma262/multipage/numbers-and-dates.html#sec-number.max_safe_integer), [Rust reference cycles](https://doc.rust-lang.org/book/ch15-06-reference-cycles.html), and [Python type hints](https://docs.python.org/3/library/typing.html) explain some target-specific constraints. Treat snapshot and journal interoperability as explicit capabilities with their own cross-runtime tests.

Qualify each portable capability using the same model declarations and scenarios.
The [portable contract](../conformance/portable/CONTRACT.md) lists the rules every target shares, the
details each language may handle its own way, and the scenarios that check them on Java, C#, and TypeScript.
Use the equivalent traversal strategy in each runtime when comparing graph walks.
The [graph traversal contract](observing-models.md#traversing-a-model-graph)
identifies the Java and C# entry points and their native defaults.
For journal support, exercise both recording and replay against an independent
checkpoint using fixtures produced by another target. The
[journal behavior contract](change-journal.md#runtime-behavior-contract) defines
how to check callback traces, native history, and portable replay for the same
edits. The TypeScript workshop gate qualifies shared snapshot and plain JSON
interchange. Journal support needs the journal conformance cases.

## Rendering

The current Java generator uses the relocated Velocity 1.7.5 dependency. Its templates receive Java-oriented objects, call helper methods, and compose runtime code through nested templates. Keep that path as the Java baseline during the first backend implementation. Prove the C# renderer on the same model fixture before making an engine a dependency of another backend.

| Option | Fit for VMF |
| --- | --- |
| Apache Velocity 2.x | First choice for a template-based C# pilot. Familiar syntax and strict rendering suit reviewed file templates over prepared inputs. A migration of the Java renderer is a separate compatibility change because whitespace, macro behavior, and engine APIs differ. [User guide](https://velocity.apache.org/engine/2.4.1/user-guide.html#strict-rendering-mode) · [Upgrade guide](https://velocity.apache.org/engine/2.4.1/upgrading.html) |
| StringTemplate 4 | Strongest template alternative when tighter separation of formatting and generation logic is useful. ANTLR uses it for target-specific generators. Configure delimiters and test ordinary generic syntax such as `<T>`. [StringTemplate expressions](https://github.com/antlr/stringtemplate4/blob/master/doc/templates.md) · [ANTLR C# templates](https://github.com/antlr/antlr4/blob/master/tool/resources/org/antlr/v4/tool/templates/codegen/CSharp/CSharp.stg) |
| Apache FreeMarker | Capable template alternative with macros and explicit handling of missing values. Its richer expression language is useful when a renderer needs it and increases the amount of behavior reviewers must inspect inside templates. [Expression reference](https://freemarker.apache.org/docs/dgui_template_exp.html) |
| Structured Java writer | Useful for target-specific identifiers, literals, imports, generic signatures, and short code blocks. It can also render a complete backend if statement-level rendering dominates. Templates can still render file scaffolding. |

Resolve types and check capabilities in Java before rendering. Apply target-specific quoting and naming in backend code. Keep templates concerned with layout and composition. Check that generated output compiles, is deterministic for the same input, and reports missing render inputs clearly.

## Delivery order

1. Add the internal model projection and one C# backend slice. Generate the existing Workshop and Station fixture. Compile both targets and compare containment moves, opposite updates, identity-based membership, and multiplicity violations.
2. Exercise a model-bounded generic relationship with a named concrete subtype. Exchange reference-aware JSON between Java and C# in both directions. Use [shared fixtures](../examples/csharp-workshop/fixtures) for stable wire aliases, forward references, identity, and opposite reconstruction. Require a diagnostic when a target cannot satisfy the model.
3. Qualify TypeScript against the same JSON fixtures and check plain external JSON on all three targets. Keep the encoded contract independent of native API choices. The [three-target check](../examples/csharp-workshop/README.md) establishes this for the initial shared model capabilities. Expand C# conformance incrementally, including read-only projections and immutable models.
4. Use the backend implementations to inform a VMF-authored metamodel. Move the Java backend to it with generated-code and behavioral parity checks. Qualify self-hosting with a pinned seed and deterministic regeneration before it becomes part of the normal build.

## Branch synchronization

Develop on `feature/multi-target-codegen` and bring in shared VMF features from `main` as they land. Rebase an unpublished branch when useful. Merge upstream into a shared branch to preserve its published history. Change the common model semantics and shared fixtures once, then use backend diagnostics and conformance runs to expose any coverage gaps. Use the repository's local qualification and artifact workflows while the branch is in development. [VMF release workflow](RELEASING.md#snapshots)
