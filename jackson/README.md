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

## Managed maps, sets, and keyed entries

JSON and YAML store `VMap<String,V>` as an object. Other scalar key types use an array of entries. For example, `VMap<Integer,String>` can contain `[{"key":7,"value":"ready"},{"key":9,"value":null}]`. The reader decodes each key using its declared type and rejects duplicate keys in the entry array. XML stores both forms as a sequence of `entry` elements.

When reading a JSON or YAML model document, the module rejects repeated field names, including repeated `key` or `value` fields within one entry. Use the strings `"NaN"`, `"Infinity"`, and `"-Infinity"` for explicit non-finite floating-point keys or members. A numeric literal that exceeds the declared numeric range is rejected.

```xml
<readings>
  <entry><key>outlet temperature</key><value>21.5</value></entry>
  <entry><key>room:2</key><null>true</null></entry>
</readings>
```

The declared VMF map value type determines how the reader interprets `value`. An empty string has an empty `value` element. An empty managed map has an empty property element. Text containing a carriage return, an XML-forbidden character, or an isolated Java surrogate uses `key64` or `value64`. These fields contain the Base64 encoding of the original UTF-16 code units in big-endian order.

An unordered scalar set uses a JSON/YAML array or XML `member` elements. Duplicate or null members are rejected. Ordered unique lists retain their list order. The generated JSON Schema includes cardinality bounds and emits `uniqueItems` when JSON equality matches scalar membership. Java distinguishes positive and negative floating-point zero, so VMF checks floating-point uniqueness during deserialization.

A keyed containment list stores its entry objects in order. Enable `VMFJacksonModule.withReferences(true)` when an entry uses a mutable model reference as its key, so reload can resolve the original object within the graph. The schema requires the key field on entries in that list. VMF enforces uniqueness across those entry keys and across the keys of typed maps during loading. JSON Schema cannot express uniqueness by one field of an object array.

An ordered or unordered unique containment end writes its children inline. An independent unique reference end writes an array of target IDs with `withReferences(true)`. The target must be reachable through containment from the serialized root. Each ID resolves to that same model object, including when distinct targets have equal fields. Null, duplicate, unresolved, or wrong-type IDs fail on read. Saving a nonempty unique reference end with references disabled fails explicitly. An immutable object held only by value has no containment identity for a portable reference.
