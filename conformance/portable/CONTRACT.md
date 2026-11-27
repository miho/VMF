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
| equality-modes | N1, N2 (the language's own equality follows `@VMFEquals` and `@VMFModel(equality = ...)`) | optional |
| identity-equality | N1, N3 (the language compares objects by identity only) | optional |
| json | J (snapshot documents that every target writes and reads alike) | optional |
| validation | L (declared multiplicities and value constraints) | optional |
| collections | K (sets, ordered sets, bounded lists, maps, keyed containment) | optional |

A target claims the modules it implements. Scenarios of modules it does not claim are skipped and reported
as skipped, not as passed.

## Language mappings

Every target documents how the model and the rules of the modules it claims appear in its language: names,
creation, lists, numbers, rejected edits, equality, listeners, recording and documents. The rules say what
must hold; the mapping says how to reach it in that language.

- [Java](../../docs/targets/java.md) (the reference)
- [C#](../../docs/targets/csharp.md)
- [TypeScript](../../docs/targets/typescript.md)

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
- **T6** `byte` is 8-bit and `short` 16-bit, both signed; `float` is IEEE-754 binary32; `char` is one UTF-16 code
  unit. Their defaults are `0`, `0`, `0.0` and U+0000. A language without these types maps them to a wider
  type that holds every value of the narrower one (the language mapping says which); values written to a
  document stay within the narrower type's range. Documents write `byte`, `short` and `float` as JSON
  numbers and a `char` as a string of one code unit.
- **T7** A subtype can declare an inherited property again. With the same type it stays one property: stored
  once, listed once (F1), and a `@DefaultValue` on the redeclaration is the default of the subtype's new
  objects. A getter-only property of an `@InterfaceOnly` supertype can be declared again with a narrower model
  type, or by each implementing type as its end of a containment (`@Contains` or `@Container`); the property
  then behaves as the implementing type declares it.
- **T8** A subtype binds the type parameters of a generic supertype to its type arguments. A property declared
  with a type parameter behaves as a property of the argument type: a `Long` keeps all 64 bits, a `Double` is
  binary64, strings and immutable values stay values, and a model type makes the property hold objects of
  that type. Documents write the property as one of the argument type.

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
- **E7** The listeners of one object are called in the order in which they were added, whether they observe
  the object alone or also the objects it contains.
- **E8** A change reaches the listeners of the changed object first, then the recursive listeners of its
  container, then those of the container's container: nearest first. The order in which listeners of
  different objects were added does not matter.
- **E9** A listener may edit the model. The edit takes effect at once, and its changes are delivered before the
  current change reaches the remaining listeners. A listener called later may therefore receive a change whose
  new value is no longer current.

## History (optional)

- **H1** While a recording on an object is active, each edit of that object or of an object it contains is
  recorded once, including its containment and opposite side effects. An edit made through the child's
  container property is recorded by the owner's recording. If both sides of an opposite pair are recorded,
  only the side the edit was made on records it.
- **H2** Undoing all recorded changes, in reverse order, restores the state before them: values,
  containment (including the previous owner and list position of a moved object) and opposites.
- **H3** Redoing them in recorded order restores the state after them.
- **H4** An edit made by a listener is recorded after the edit whose delivery triggered it. Undoing all recorded
  changes in reverse order therefore restores the state before them also when listeners edited the model.

## Reflection (optional)

- **F1** Properties are listed in this order: if a type gives each of its own properties a `@PropertyOrder`
  index, the properties it inherits come first, in the order of the supertypes that declare them, then its own
  by index; otherwise all its properties, inherited ones included, are sorted alphabetically.
- **F2** `@Annotation` key/value pairs of types and properties are available in declaration order;
  repeated keys are allowed. Keys starting with `vmf:` are reserved for VMF and ignored by the scenarios.

## Equality modes (optional)

- **N1** Every model type has an equality mode: `INSTANCE` is identity; `CONTAINMENT_AND_EXTERNAL` is content
  equality (Q1); `ALL` is content equality that also takes references into account. `@VMFModel(equality = ...)`
  sets the mode for the whole model; `@VMFEquals` overrides it per type. Without either, the mode is `INSTANCE`.
- **N2** In a language that lets a type define its own equality, that equality follows the mode: Java
  `equals`/`hashCode`, C# `Equals`/`GetHashCode`.
- **N3** In a language that compares objects by identity only, the language's equality stays identity in every
  mode, and content equality (Q1) remains available as an operation. The target's language mapping says so.

A target claims `equality-modes` (N1, N2) or `identity-equality` (N1, N3), whichever its language allows.
Java and C# claim `equality-modes`. TypeScript claims `identity-equality`: JavaScript has no equality that a
class can define; `===`, `Map` and `Set` compare objects by identity.

## JSON (optional)

A snapshot document is the JSON form of a model and its containment tree. Documents written by one target
are read by every other target.

- **J1** Each model object is a JSON object; its properties are fields named by their wire name (the model
  name, or the value of `vmf:jackson:rename`). Primitive values and lists are always written, an empty list
  as `[]`; an absent single value (`null`) is omitted. Integers are exact, including all 64 bits of `long`;
  doubles are JSON numbers, and readers accept `2` as well as `2.0`. Field order carries no meaning.
- **J2** Contained objects are nested where they are contained. A reference is written as the `@vmf-id` of its
  target, a string. Writers number the referenced objects `"1"`, `"2"`, ... in the order in which they occur in
  the document; reference lists keep their order and repeats. A reference to an immutable value is written by
  value, as a nested object without `@vmf-id`, in plain documents too: immutable values have no identity.
- **J3** An object whose declared type has subtypes carries `@vmf-type`: the type's wire alias
  (`vmf:wire:type`) or its qualified name.
- **J4** Readers restore an equal model with the same references from any document that follows J1 to J3,
  and reject unknown type names, unresolved ids and duplicate ids.
- **J5** A plain document is the same without `@vmf-id` and `@vmf-type`. A model with a non-empty reference to
  a mutable object cannot be written as a plain document: by default the writer rejects it. Every target offers
  an explicit option to leave such references out instead; the document then holds the containment tree only.
- **J6** Writers reject a reference to an object outside the written containment tree.

## Validation (optional)

Validation checks a model against its declared multiplicities (`@Multiplicity`) and value constraints (`@Range`,
`@Length`, `@Pattern`) and reports each violation as an issue. It does not change the model; a model may be
invalid while it is being built.

- **L1** Validating an object checks the object and every object contained in it, directly or indirectly, each
  once. References and container properties are not followed: an object that is only referenced is not checked,
  and neither is the container of the validated object.
- **L2** A property whose number of values is below its lower bound or above its upper bound is reported as
  `vmf.multiplicity`. A single-valued property has no value when it is `null` and one value otherwise; a list
  has as many values as elements.
- **L3** `@Range` on a byte, short, int, long, float or double property, primitive or boxed, is reported as
  `vmf.range` when the value is below `min`, above `max` or equal to a bound declared exclusive. The bounds are
  decimal numbers and comparisons are exact: an integer is compared with all its digits, the 64 bits of a `long`
  included; a float or double is compared by its shortest decimal representation (the digits a JSON writer
  writes), so the double `0.1` is equal to the bound `0.1`. A float property is compared at float precision.
  NaN and infinities are outside every range.
- **L4** `@Length` on a string is reported as `vmf.length` when its number of Unicode code points, not UTF-16 code
  units, is below `min` or above `max` (`max = -1`: no maximum).
- **L5** `@Pattern` on a string is reported as `vmf.pattern` when the regular expression matches no part of the
  value. The search is not anchored: `\d` accepts every value that contains a digit. `^` and `$` anchor it, and `$`
  matches only at the very end of the value, not before a final line break. A portable pattern means the same in
  every target: printable ASCII; literal characters and escaped metacharacters; `\d` and `\w` (ASCII digits and
  word characters); `\n`, `\r`, `\t` and `\f`; classes without negation, nesting or intersection; groups `(...)`
  and `(?:...)`; alternation; the quantifiers `*`, `+`, `?`, `{n}`, `{n,}` and `{n,m}`, also lazy. The generator
  rejects other patterns unless they are declared `portable = false`. Java checks such a pattern as written; the
  other targets need an explicit counterpart in their own dialect (see the language mappings) and reject the
  model without one.
- **L6** `null` satisfies every range, length and pattern; only a lower bound (L2) requires a value.
- **L7** An issue has the code `vmf.multiplicity`, `vmf.range`, `vmf.length` or `vmf.pattern` and a path: a JSON
  pointer (RFC 6901) of model property names and list indices from the validated object to the property, such as
  `/segments/0/code`. Paths use model property names, not wire names.
- **L8** Issues are listed depth first: the validated object's issues, then those of each contained object and its
  contents, contained objects in property order and, within a list, in list order. The issues of one object follow
  its properties in reflection order (F1); for one property, `vmf.multiplicity` comes first, then `vmf.range`,
  `vmf.length` and `vmf.pattern`.

## Collections (optional)

Besides ordinary lists (T5), a model declares sets (`Set<T>`), ordered sets (`@Multiplicity(kind = ORDERED_SET)` on a
list), bounded lists (`@Multiplicity(upper = n)` on a list of scalars), maps (`VMap<K, V>` of scalar keys and values)
and keyed containment (`@Contains @Keyed(by = "key")`). With the events, history, views and json modules, the K rules
below extend those modules to these collections.

- **K1** A set of scalar values holds each value at most once and never `null`. Adding a value it holds leaves it
  unchanged and produces no event; adding `null` is rejected. Removing a value it does not hold changes nothing. The
  iteration order of a set is not specified.
- **K2** An ordered set keeps the order of a list and holds each element at most once; an ordered set of scalars holds
  no `null`. Appending an element it holds leaves it unchanged; inserting an element it holds, or replacing an element
  with one it holds at another index, is rejected.
- **K3** Sets and ordered sets of model objects compare their elements by identity: content-equal but distinct
  objects are distinct elements. With an opposite, both ends stay consistent as in R1 to R3.
- **K4** A map has non-null keys; a value may be `null`, and a key with a `null` value is present. Entries keep the
  order in which their keys were first put: putting a value for a new key appends an entry, putting a value for a
  present key replaces the value and keeps the entry's position. Removing a key removes its entry; removing an absent
  key changes nothing. A `null` key is rejected.
- **K5** Sets, ordered sets, maps and bounded lists of scalars are bounded by `@Multiplicity(upper = n)`: an edit that
  would make them hold more than n elements or entries is rejected and leaves them unchanged, also an edit that adds
  several at once. Adding a value a set holds and replacing the value of a present key do not count. These
  collections hold no `null` elements (map values excepted). An ordinary list (a list of scalars that is neither an
  ordered set nor bounded) accepts `null` elements of boxed types. Lower bounds, and the bounds of collections of model
  objects (containment and references), are left to validation: edits beyond them succeed.
- **K6** In a keyed containment, every entry has a key (its property named by `@Keyed`) that is not `null` and that
  no other entry of the same property has; scalar keys compare by value, model objects by identity. An edit that
  would add, insert or replace in an entry without a key or with a key another entry has is rejected and leaves the
  model unchanged; an entry moving in from elsewhere stays where it was. The owner finds an entry by its key
  (`find<Property>By<Key>`), which returns the entry or none. While an entry is in the list, setting its key to `null`
  or to the key of another entry is rejected; other key changes are allowed and the lookup follows them. An entry
  that left the list can change its key freely.
- **K7** Content equality (Q1) compares sets as sets and maps as sets of entries: iteration order does not matter,
  but a key with a `null` value differs from an absent key. Ordered sets and keyed lists compare in order. A deep
  copy (Q3) has the same set members and the same map entries in the same order.
- **K8** A read-only view (V1, V2) shows sets, maps and keyed lists as read-only, live collections; its key lookup
  returns read-only views. An `@Immutable` type receives its sets, ordered sets and maps at creation, checked like
  those of a mutable type (a set keeps one of repeated values), and they cannot change afterwards (V3).
- **K9** An edit of a set is reported with the added or removed values, without indices. An edit of a map is reported
  per key: a new key as an added entry, a removed key as a removed entry, a new value for a present key as a replaced
  entry, each with its key and its (new) value. Clearing reports all removed values or entries. As in E6, a bulk edit
  may be reported as one event or several.
- **K10** Edits of sets and maps are recorded and undone like other edits (H1 to H3). Undoing the removal of a map
  key restores its entry; the entry's position in the iteration order is not specified.
- **K11** A document (J1) writes a set as a JSON array of its values, in any order, and an ordered set like a list. A
  map with `String` keys is a JSON object with one field per entry, in iteration order; a map with other keys is an
  array of `{"key": k, "value": v}` objects in iteration order. A `null` value is written as `null`; empty sets and
  maps are written as `[]` and `{}` (or `[]` for keys that are not strings). Readers keep the entry order of the
  document and reject `null` and repeated keys in the array form and `null` and repeated set members. Keyed lists are
  written like containment lists; readers reject entries without a key or with a repeated key, also when the key is a
  reference that they resolve after the entry.

## Deliberately unspecified

These differ between languages, or between implementations of one language, and must not be tested
across targets:

- names and API shape: builders or object initializers, getters or properties, list types
- exception types and messages; only "rejected and unchanged" is specified
- listeners added or removed while a change is delivered, exceptions thrown by listeners, and recordings
  started or stopped inside a listener
- whether a recursive listener sees the container-property event of an object that enters, leaves or
  moves within its subtree during that edit
- adding an object to the containment list that already holds it, duplicate entries created by
  index-based replacement in containment lists, and `null` entries in containment lists
- recordings that cover only one side of a moved object (the previous owner is not covered)
- whether a deep copy shares or copies immutable values
- the iteration order of sets, and the position that undo gives a map key it puts back (K1, K10)
- whether putting a value under a present key that already holds an equal value is reported as a change
- whether `0.0` and `-0.0` (or NaN values) are distinct set elements or map keys
- JSON objects of string-key maps with repeated field names
- plain documents for models whose subtypes occur where a property declares their supertype
- validation messages, severities and what an issue carries besides its code and path; the order of the issues
  of a property whose constraints come from several declarations (a redeclared property)
- whether an edit beyond the upper bound of a scalar list is rejected (Java and C# reject it)
- `toString` formats and concrete hash code values; threading

## Not part of the cross-language contract

Target-specific features may exist but are tested by that target's own tests: builder merge semantics
(`applyFrom`, `applyTo`, appended collections), behavior delegation (`@DelegateTo`), executable defaults,
custom external value types, cursors and traversal APIs, reflection writes, journals and their file
formats, XML and YAML, model diff, validation rules beyond the declared constraints (Java's and C#'s
`ModelValidator` with application rules or reference following), collection defaults (`@DefaultValue`
of sets, lists and maps). Each of these can become a module of its own with its own rules and scenarios
once two targets need it.

The Workshop example additionally exchanges files between Java, C# and TypeScript
(`:vmf:verifyTypeScriptWorkshop`); the JSON module states and checks the format those files share.
