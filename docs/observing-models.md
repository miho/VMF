# Observing model changes

Use `vmf().changes()` for VMF change events. A change identifies its source object and
property and carries either old and new property values or the affected list entries.

```java
Changes changes = root.vmf().changes();
var local = changes.addLocalListener(handler);
var subtree = changes.addRecursiveListener(handler);
var titles = changes.addPropertyListener("title", handler, Changes.Scope.RECURSIVE);
var listEdits = changes.addFilteredListener(
        change -> change.listChange().isPresent(), handler, Changes.Scope.RECURSIVE);
```

Local listeners see edits to the root, including its list properties. Recursive listeners
also follow the current containment subtree. A reference property emits changes on its
owner. Adding or removing a reference has no effect on which descendants are observed.
A named listener without a scope observes
the root property. With recursive scope, VMF matches the name on every source object,
including when the root has no property with that name. Keep each subscription and call
`unsubscribe()` when the consumer is done.
Each VMF change subscription removes its own registration. The same callback can
be registered more than once, and calling one handle twice is safe.
Subscriptions from generated ordinary VMF lists also remove a single registration.

Listeners and predicates run synchronously during model change delivery. Filtering limits
which changes reach one listener. Choose local scope when observations only concern the
root. JavaBeans property-change listeners continue to support existing beans integrations.
When a scope has only exact-name subscriptions and recording is off, VMF can skip its
`Change` payload for an unmatched property. Recursive observation still follows containment
edits, and recordings include changes regardless of the subscriber filters.

### Direct property callbacks

JavaBeans listeners and C# `VmfChanges.AddDirectPropertyListener` observe
property assignments on one object and receive its old and new values.
Ordinary direct registrations interleave with model observation in source
order. Named direct callbacks run after those registrations for the
matching property. Each property event captures its direct recipients
before callbacks run. A nested edit or a later inverse-side event captures
its own recipients. Direct and modeled property callbacks on a read-only
model receive its canonical view. If an old or new value is a generated
mutable model, the callback receives that value's canonical read-only view.
This also applies when the declared property type is Object and the payload
comes from another generated model package. Mutable callbacks and ordinary
getters for Object properties preserve the original value. Existing read-only
views, immutable models, and other values retain their identity.

[Shared direct property traces](../conformance/fixtures/direct-property-observers.json)
cover registration order, reentry, observer errors, and starting a recorder
from a callback.

### Collection callback order

Direct and modeled callbacks on both views of an `Object[]` property report each
entry with its exact stored identity, including mutable models from other generated packages.

Ordinary lists and scalar maps interleave direct collection callbacks and
mutable model callbacks in the source's registration order. Obtaining a
read-only collection reserves its relay in that order. Observing the
read-only model can obtain the projection on the listener's behalf.
The projected relay reads its own subscribers when it runs. The
[mixed-list traces](../conformance/fixtures/mixed-list-observers.json) and
[scalar membership traces](../conformance/fixtures/scalar-membership-observers.json)
cover putting a subscription before or after that relay and changing it
during a callback.

An unordered scalar set captures its mutable and projected callback lists
before delivery, then visits the mutable callbacks first. An unordered
identity reference or owning set installs the modeled projection relay
when the first read-only model observer subscribes, independent of when
the set view is read. Ordered keyed relationships
also attach their modeled projection when a read-only model observer
subscribes. The C# direct collection API for those relationships can
independently observe indexed edits. The
[identity-set traces](../conformance/fixtures/model-identity-set-observers.json)
and [keyed and reciprocal traces](../conformance/fixtures/modeled-relationship-set-observers.json)
exercise these positions and reentrant subscription changes.

A shallow copy can share the physical list with its original. The list
delivers one direct edit, and each observed model owner receives its own
modeled change. Reciprocal lists capture the source routes before
repairing the inverse. The initiating route records native history.
The opposite route can deliver its own live notifications.

### Delivery, mutation and failure

Live callbacks run inside the mutator. During reparenting, a source-list removal listener
can still see the old parent. By the time the destination-list addition reaches its listeners,
the new parent has been assigned. Read the graph after the mutator returns for its completed
state. Recursive membership is reconciled after delivery, including when a callback throws.
While VMF is updating parent links, a nested attempt to overwrite the pending scalar
containment slot or mutate the destination list raises `IllegalArgumentException`.
The destination list can be edited again from its ordinary post-update notification.
For opposite references, a competing scalar setter can also be rejected while its
partner link is pending. A reciprocal notification runs after the inverse is installed.

A throwing live model observer reports a failure to the mutating caller. VMF continues
delivery to the other live model observers and completes the containment bookkeeping.
The first unchecked observer failure
propagates with later failures available through `getSuppressed()`. Applied writes remain
on the graph. Inspect the result before deciding whether to retry.

Keep observers read-only when the application requires a portable journal with a prescribed
write order. Put related writes in the editing code. For scalar value setters, native history
preserves the initiating write before a nested write from a VMF change callback when VMF
receives the initiating event first. Structural callbacks can still edit a child during
its detach notification, and an earlier raw JavaBeans listener can run before the recorder
receives the initiating event. Shadow verification checks the captured sequence and can
reject it. Publish a verified frame from the writer after the mutators have returned.
[Portable change journals](change-journal.md)

Regenerate previously generated model sources with the matching VMF generator and
runtime to obtain these containment and opposite-reference protections and the
updated raw list notification behavior.

## Reflecting on property declarations

Call `model.vmf().reflect().propertyMetadata()` or
`ModelType.type().reflect().propertyMetadata()` to inspect names, type information and
annotations. Each call returns a detached immutable snapshot in reflection order.
Use `properties()` or `propertyByName(...)` when you need reflective value access or a
subscription bound to a property on a model instance.

## Traversing a model graph

Java's `root.vmf().content().stream()` and `iterator()` use `UNIQUE_NODE`.
The corresponding C# enumeration is
`ModelSnapshot.Reflection.Traverse(root, ModelTraversalStrategy.UniqueNode)`.
`ModelSnapshot.Reflection.OpenGraphCursor(root)` makes the same selection on a
writable graph and supports edits to the selected edge. Both runtimes
walk containment and reference edges depth first, skip inverse container
edges, and visit each mutable identity once. Immutable values still appear
at each occurrence. Enumeration also supports read-only projections.

C# `ModelSnapshot.Reflection.Traverse(root)` uses its native default,
`UniqueProperty`. It yields distinct edge occurrences, including aliases and
back edges, while expanding each mutable identity once. To compare these
occurrences across runtimes, select `UNIQUE_PROPERTY` in Java and
`UniqueProperty` in C#. Choose `CONTAINMENT_TREE` or `ContainmentTree` to
visit only owning edges and retain repeated owning slots.
