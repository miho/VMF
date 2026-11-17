# README #

[ ![Download](https://api.bintray.com/packages/miho/VMF/VMF-Runtime/images/download.svg) ](https://bintray.com/miho/VMF/VMF-Runtime/_latestVersion)

## Reference-aware JSON and schema

Reference support is opt-in on both the mapper module and schema generator:

```java
var mode = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
var mapper = new ObjectMapper().registerModule(
        VMFJacksonModule.newInstance(mode).withReferences(true));
var schema = VMFJsonSchemaGenerator.newInstance(mode)
        .withReferences(true).generateSchema(MyModel.class);
```

Use the same type aliases on both objects when aliases are configured. The
draft-07 schema describes `@vmf-id` and scalar/list reference IDs, while immutable
model values remain objects written by value, including immutable implementations
of an interface-only reference type. The native reader checks ID uniqueness and
resolution and restores opposite relationships. It does not validate the
consistency of every supplied opposite; those graph relationships are not
expressed by JSON Schema. Containment-only schemas remain the default.
