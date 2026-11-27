# Portable VMF contract (draft 1)

A VMF model is declared once, as Java interfaces. This contract says what every generated target language
(Java, C#, TypeScript, ...) must do with such a model so that the model can be recreated in that language
and behaves the same way where it matters.

The contract is about **what the model looks like after an edit**, not about how or when an implementation
gets there. Naming, API shape, exception types, listener timing and other language habits stay with each
language. Every rule has an id; the scenarios in [`scenarios/`](scenarios) cite the ids they check, and
every target, Java included, runs the same scenarios ([README](README.md)).

## Modules

| Module | Rules | Required |
|---|---|---|
| core | T (types and values), C (containment), R (references), Q (equality and copies) | yes |
| views | V (read-only views, immutable types) | optional |
| events | E (change listeners) | optional |
| history | H (recording, undo, redo) | optional |
| reflection | F (property order, annotations) | optional |
| equality-modes | N (`@VMFEquals` and `@VMFModel(equality = ...)` for the language's own equality) | optional |

A target claims the modules it implements. Scenarios of modules it does not claim are skipped and reported
as skipped, not as passed.

## Core

### T: Types and values

- **T1** Each model interface becomes a type of the target. An instance is an instance of all its declared
  model supertypes, including several supertypes and `@InterfaceOnly` ones. `@InterfaceOnly` types cannot be
  instantiated. A property declared on a supertype behaves the same on every subtype.
- **T2** A new object has default values: `false`, `0`, `0.0` for primitives; `null` for strings, boxed
  scalars and single-valued model properties; an empty, never `null`, list for list properties.
  `@DefaultValue` replaces the default and can be overwritten like any value.
- **T3** An object can be created with initial values (a builder or its equivalent). The result is the same as
  creating the object and then setting those values; contained children given this way are moved as in C2.
- **T4** A single-valued property returns the value last set, including `null` where the type allows it.
  `int` is 32-bit, `long` 64-bit (all 64 bits are kept), `double` IEEE-754 binary64, strings are Unicode text.
- **T5** Lists keep insertion order. Scalar lists and plain reference lists may hold the same value several
  times. Index-based insert, replace and remove work as usual.

### C: Containment

`@Contains` declares a containment property; `@Container` on the other type declares the property that points
back to the container (its opposite).

- **C1** Setting or adding an object into a containment property makes the owner its container. The declared
  container property of the child returns the owner.
- **C2** An object has at most one container. Placing it into any containment property (of another owner, or
  another property of the same owner, single-valued or list) first removes it from its previous place; the
  previous place no longer holds it and the previous container property returns `null`.
- **C3** Removing a contained object (setting the property to `null`, removing it from the list, clearing the
  list, or replacing it with another object) clears its container property.
- **C4** Setting a child's container property to an owner is the same as adding the child to the matching
  containment property (lists append); setting it to `null` removes the child.
- **C5** An edit that would make an object contain itself, directly or through its descendants, is rejected
  and leaves the model unchanged.

### R: References

`@Refers(opposite = ...)` declares a pair of properties that always point at each other.

- **R1** Setting or adding a reference updates its opposite on the referenced object, for one-to-one,
  one-to-many and many-to-many pairs, from either side.
- **R2** Repointing a single-valued reference removes the old target's opposite. In a one-to-one pair, the new
  target's previous partner loses its reference.
- **R3** Removing a reference (set to `null`, remove from list, clear) removes its opposite.
- **R4** A reference without an opposite never changes the referenced object. References never change
  containment.

### Q: Equality and copies

- **Q1** Content equality compares the type, the scalar values and the contained children, recursively and
  in list order. Plain references and the container are not compared. Content-equal objects have the same
  content hash code. Content equality is reflexive, symmetric and transitive.
- **Q2** Objects of different types are not content-equal, even if all shared properties are equal.
- **Q3** A deep copy creates new objects for the root and everything reachable from it through containment
  and references, but not through container properties: the copy of the root has no container. References
  between copied objects point to the copies. The copy is content-equal to the original and independent of it.
- **Q4** Equal objects stay distinct: containment, moves, removals and replacements act on the identical
  object, never on another object that is merely equal to it.

## Views (optional)

- **V1** A read-only view of an object shows its current state, including later changes, and its contained
  objects are shown as read-only views too.
- **V2** A read-only view offers no way to change the model: every edit through it is rejected (or does not
  exist in the API) and leaves the model unchanged.
- **V3** `@Immutable` types are created with all their values and cannot be changed afterwards, including
  their lists. A `@GetterOnly` property inherited by a mutable type is writable there.

## Events (optional)

- **E1** A listener on an object receives an event for each property of that object that an edit changes.
  An event names the object, the property and either the old and new value or, for lists, the added,
  removed or replaced elements with their indices. Events are delivered after the change is visible.
- **E2** Setting a property to the value it already has (the identical object for model values) produces no
  event.
- **E3** Containment and opposite updates are reported on both sides: the owner's property and the child's
  container property (or the opposite reference) each produce events for their own objects' listeners.
- **E4** A recursive listener on an object also receives the events of all objects contained in it at the
  time of the event; an object that leaves the subtree is no longer observed afterwards.
- **E5** A removed listener receives no further events.
- **E6** A bulk list edit may be reported as one event or several. One property change of an edit may be
  reported as a chain (`a -> null`, `null -> b`). Runners merge both forms before comparing.

## History (optional)

- **H1** While a recording on an object is active, each edit of that object or of an object it contains is
  recorded once, including its containment and opposite side effects. An edit made through the child's
  container property is recorded by the owner's recording. If both sides of an opposite pair are recorded,
  only the side the edit was made on records it.
- **H2** Undoing all recorded changes, in reverse order, restores the state before them: values,
  containment (including the previous owner and list position of a moved object) and opposites.
- **H3** Redoing them in recorded order restores the state after them.

## Reflection (optional)

- **F1** Properties are listed in this order: properties of supertypes before the type's own; within a
  type, by `@PropertyOrder` index if all are given, otherwise alphabetically.
- **F2** `@Annotation` key/value pairs of types and properties are available in declaration order;
  repeated keys are allowed. Keys starting with `vmf:` are reserved for VMF and ignored by the scenarios.

## Equality modes (optional)

- **N1** The language's own equality (Java `equals`/`hashCode`, C# `Equals`/`GetHashCode`) follows the
  declared mode: `INSTANCE` is identity; `CONTAINMENT_AND_EXTERNAL` is content equality (Q1);
  `ALL` also takes references into account. `@VMFModel(equality = ...)` sets the mode for the whole
  model; `@VMFEquals` overrides it per type.

## Deliberately unspecified

These differ between languages, or between implementations of one language, and must not be tested
across targets:

- names and API shape: builders or object initializers, getters or properties, list types
- exception types and messages; only "rejected and unchanged" is specified
- the order in which several listeners are called, edits made inside listeners (reentrancy), exceptions
  thrown by listeners
- whether a recursive listener sees the container-property event of an object that enters, leaves or
  moves within its subtree during that edit
- adding an object to the containment list that already holds it, duplicate entries created by
  index-based replacement in containment lists, and `null` entries in containment lists
- recordings that cover only one side of a moved object (the previous owner is not covered)
- whether a deep copy shares or copies immutable values
- `toString` formats and concrete hash code values; threading

## Not part of the cross-language contract

Target-specific features may exist but are tested by that target's own tests: builder merge semantics
(`applyFrom`, `applyTo`, appended collections), behavior delegation (`@DelegateTo`), executable defaults,
custom external value types, cursors and traversal APIs, reflection writes, journals and their file
formats, XML and YAML, model diff, validation, maps and sets, generic model types. Each of these can become a
module of its own with its own rules and scenarios once two targets need it.

The JSON interchange (reference-aware snapshots and plain JSON) is checked separately by the Workshop
exchange between Java, C# and TypeScript (`:vmf:verifyTypeScriptWorkshop`); a JSON module with scenarios is
the next candidate.
