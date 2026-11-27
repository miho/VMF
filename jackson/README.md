# README #

[ ![Download](https://api.bintray.com/packages/miho/VMF/VMF-Runtime/images/download.svg) ](https://bintray.com/miho/VMF/VMF-Runtime/_latestVersion)

## Model constraints

Jackson projects VMF multiplicity, numeric range, string length, and portable string patterns into Draft-07 JSON Schema. Configure `VMFJacksonModule.withModelValidator(validator)` to also validate the finished model after a read. See [model validation](../docs/model-validation.md) for the runtime API, schema extension hook, and reference behavior.

## Model-defined wire names

Put `@Annotation(key = "vmf:wire:type", value = "thermometer")` on a concrete
model declaration to share its discriminator across generated targets.
Configure the Java mapper and schema generator with
`withModelWireTypeAliases(Catalog.class)`, where `Catalog` is any concrete
generated type from that model. This registers the model's aliases when
polymorphism calls for a discriminator. Explicit `withTypeAlias` configuration
remains available for existing callers.

Use `@Annotation(key = "vmf:jackson:rename", value = "items")` for a property
whose wire name differs from its model name. See the
[Java and C# interchange example](../examples/csharp-workshop/README.md#exchange-json-snapshots).

## Reference-aware JSON and schema

Reference support is opt-in on both the mapper module and schema generator:

```java
var mode = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
var mapper = new ObjectMapper().registerModule(
        VMFJacksonModule.newInstance(mode).withReferences(true));
var schema = VMFJsonSchemaGenerator.newInstance(mode)
        .withReferences(true).generateSchema(MyModel.class);
```

The default mapper reads and writes contained models and immutable values as ordinary nested objects and arrays. It cannot represent a non-containment reference to a mutable object and rejects a model that has one when writing. Choose another `VMFJacksonModule.ReferenceMode` to change that: `withReferences(true)` (`ReferenceMode.IDS`) writes such references as ids, and `withReferences(ReferenceMode.OMIT)` writes the containment tree without them. For a property declared as `Set<Label>` with immutable `Label` elements, an existing JSON array such as `[{"text":"A"},{"text":"A"}]` produces two distinct labels. Field renames and type aliases apply in either mode.

Enable references when several fields must point to the same restored instance. In this document, `current` and the first entry of `history` share a label. The second entry is another label with the same text.

```json
{"current":{"@vmf-id":"1","text":"A"},"history":["1",{"text":"A"}]}
```

VMF assigns document-local IDs to shared immutable objects and immutable members of unique reference ends. It writes the first occurrence inline and subsequent occurrences as IDs. A single-use immutable scalar or sequence element retains its ordinary object shape. The reader accepts forward references and older by-value documents. Nested immutable values are constructed with their resolved targets. A cyclic immutable definition fails during reading.

JSON and YAML use `@vmf-id`. XML uses `vmf-id`. In an XML model list, `<item/>` represents an empty concrete immutable object and `<item xsi:nil="true"/>` represents a null slot. An empty list has an empty outer property element. Older XML that used the same bare element for a null immutable slot and an empty immutable object reads that element as an empty object. The draft-07 schema describes the object and ID alternatives. Use the same reference setting and type aliases on the mapper and schema generator. The native reader checks ID uniqueness, resolution, type and reference-set membership, and restores opposite relationships.

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

An ordered or unordered unique containment end writes its children inline. A unique reference end with mutable targets requires `withReferences(true)`. Each mutable target must be reachable through containment from the serialized root. An immutable target can be defined inline at any of its references. Null members, duplicate identities, unresolved IDs and wrong-type IDs fail on read.

## Portable change journals

`VMFJournalCodec` reads and writes preview journals in JSON, YAML, and XML. All current operation and value kinds use envelope version `1`. The reader accepts only that version and rejects the retired preview versions `2` through `6`. The model version in the journal is separate. [Journal formats and replay](../docs/change-journal.md)
