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

Listeners and predicates run synchronously during model change delivery. Filtering limits
which changes reach one listener. Choose local scope when observations only concern the
root. JavaBeans property-change listeners continue to support existing beans integrations.
When a scope has only exact-name subscriptions and recording is off, VMF can skip its
`Change` payload for an unmatched property. Recursive observation still follows containment
edits, and recordings include changes regardless of the subscriber filters.

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
