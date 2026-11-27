import {
    idField,
    type ModelConstructor,
    SnapshotRegistry,
    type SnapshotProperty,
    type SnapshotType,
    typeField,
} from "./snapshot-registry.js";
import { canonicalUuid, fromWire, toWire, type WireValue } from "./json-values.js";
import { childPath, JsonNumber, type JsonValue, parseJson, requireValidText } from "./wire-json.js";

type ScalarProperty = Extract<SnapshotProperty, { kind: "scalar" | "scalarList" }>;
type Scalar = string | number | boolean | bigint;
type Encoded = Scalar | WireValue | Encoded[] | { [key: string]: Encoded };
const snapshotFields = new Set([typeField, idField]);

interface Definition {
    readonly instance: object;
    readonly type: SnapshotType;
    readonly expectedType: ModelConstructor<object>;
    readonly fields: readonly Field[];
}

/** An immutable value in a reference position: written by value, without an id. */
class ImmutableValue {
    constructor(readonly definition: Definition) {
    }
}

type Field =
    | { name: string; kind: "scalar"; value: Encoded }
    | { name: string; kind: "containment"; value: Definition }
    | { name: string; kind: "containmentList"; value: Definition[] }
    | { name: string; kind: "reference"; value: object }
    | { name: string; kind: "value"; value: Definition }
    | { name: string; kind: "referenceSet"; value: object[] }
    | { name: string; kind: "scalarList"; value: Encoded[] }
    | { name: string; kind: "referenceList"; value: (object | ImmutableValue)[] };

const minInt64 = -(2n ** 63n);
const maxInt64 = 2n ** 63n - 1n;

interface ReferenceUse {
    readonly target: object;
    readonly path: string;
}

interface PendingReference {
    /** The id of the target, or undefined for an immutable value read in place. */
    readonly id?: string;
    readonly value?: object;
    readonly path: string;
    readonly type: ModelConstructor<object>;
    readonly assign: (target: object) => void;
}

/**
 * How a document treats non-containment references to model objects: "ids" writes them as `@vmf-id` strings,
 * "reject" fails to write a model that has one, and "omit" leaves them out.
 */
export type ReferenceMode = "ids" | "reject" | "omit";

/** Reads and writes VMF snapshots using only the explicitly registered model types. */
export class SnapshotJson {
    static write(root: object, registry: SnapshotRegistry): string {
        return write(root, nativeType(root, "$"), registry, true);
    }

    static read<T extends object>(json: string, rootType: ModelConstructor<T>, registry: SnapshotRegistry): T {
        return read(json, rootType, registry, true);
    }

    /**
     * Writes a typed document with no VMF metadata. Ambiguous types are rejected; references are rejected unless
     * `references` is "omit", which leaves them out.
     */
    static writePlain<T extends object>(root: T, rootType: ModelConstructor<T>, registry: SnapshotRegistry,
                                        references: Exclude<ReferenceMode, "ids"> = "reject"): string {
        if (references !== "reject" && references !== "omit") {
            throw new TypeError(`Plain JSON rejects or omits references, not '${String(references)}'.`);
        }
        return write(root, rootType, registry, false, references === "omit");
    }

    static readPlain<T extends object>(json: string, rootType: ModelConstructor<T>, registry: SnapshotRegistry): T {
        return read(json, rootType, registry, false);
    }
}

function write(root: object, rootType: ModelConstructor<object>, registry: SnapshotRegistry, references: boolean,
               omit = false): string {
    const contained = new Set<object>();
    const uses: ReferenceUse[] = [];
    const definition = capture(root, rootType, "$", registry, references, contained, uses, omit);
    const targets = new Set<object>();
    for (const use of uses) {
        if (!contained.has(use.target)) {
            throw invalid(use.path, "Reference target is outside the root containment tree");
        }
        targets.add(use.target);
    }

    const ids = new Map<object, number>();
    for (const node of contained) {
        if (targets.has(node)) {
            ids.set(node, ids.size + 1);
        }
    }
    return stringify(encode(definition, registry, references, ids));
}

function capture(instance: object, expectedType: ModelConstructor<object>, path: string, registry: SnapshotRegistry,
                 references: boolean, contained: Set<object>, uses: ReferenceUse[], omit: boolean): Definition {
    const actualType = nativeType(instance, path);
    const type = registry.typeForClass(actualType, path);
    requireModel(instance, expectedType, path);
    if (!references && actualType !== expectedType) {
        throw invalid(path, `Plain JSON requires a discriminator to restore '${type.wireName}' as '${expectedType.name}'`);
    }
    // Immutable values are written where they are used; they are not part of the containment tree.
    if (type.immutable !== true) {
        if (contained.has(instance)) {
            throw invalid(path, "Object occurs more than once in the containment tree");
        }
        contained.add(instance);
    }

    const fields: Field[] = [];
    for (const property of type.properties) {
        const name = property.wireName;
        const fieldPath = childPath(path, name);
        const value = property.get(instance);
        switch (property.kind) {
            case "scalarList": {
                const items = [...(value as Iterable<unknown>)].map((item, index) => {
                    const itemPath = childPath(fieldPath, index);
                    if (item === null || item === undefined) {
                        if (!allowsNull(property)) {
                            throw invalid(itemPath, `Expected a JSON value of type '${property.scalarType}'`);
                        }
                        return null;
                    }
                    return writeScalar(item, property, itemPath);
                });
                fields.push({ name, kind: property.kind, value: items });
                break;
            }
            case "referenceList": {
                const modelType = property.modelType();
                const members = [...(value as Iterable<unknown>)].map((item, index) =>
                    requireModel(item, modelType, childPath(fieldPath, index)));
                const mutable = members.some(member => !isImmutable(member, registry, fieldPath));
                if (mutable && !omit) {
                    requireReferences(references, fieldPath);
                }
                if (references || (!mutable && members.length > 0)) {
                    fields.push({ name, kind: property.kind, value: members.map((member, index) => {
                        const memberPath = childPath(fieldPath, index);
                        if (isImmutable(member, registry, memberPath)) {
                            return new ImmutableValue(capture(member, modelType, memberPath, registry, references,
                                contained, uses, omit));
                        }
                        uses.push({ target: member, path: memberPath });
                        return member;
                    }) });
                }
                break;
            }
            case "scalar":
                if (value === null || value === undefined) {
                    if (!allowsNull(property)) {
                        throw invalid(fieldPath, `Expected a JSON value of type '${property.scalarType}'`);
                    }
                    if (property.writeNull === true) {
                        fields.push({ name, kind: property.kind, value: null });
                    }
                } else {
                    fields.push({ name, kind: property.kind, value: writeScalar(value, property, fieldPath) });
                }
                break;
            case "containment":
                if (value !== null && value !== undefined) {
                    const modelType = property.modelType();
                    fields.push({ name, kind: property.kind, value: capture(requireModel(value, modelType, fieldPath),
                        modelType, fieldPath, registry, references, contained, uses, omit) });
                }
                break;
            case "containmentList": {
                const modelType = property.modelType();
                const children = readMembers(value, modelType, fieldPath).map((member, index) =>
                    capture(member, modelType, childPath(fieldPath, index), registry, references, contained, uses, omit));
                fields.push({ name, kind: property.kind, value: children });
                break;
            }
            case "reference":
                if (value !== null && value !== undefined) {
                    const target = requireModel(value, property.modelType(), fieldPath);
                    if (isImmutable(target, registry, fieldPath)) {
                        fields.push({ name, kind: "value", value: capture(target, property.modelType(), fieldPath,
                            registry, references, contained, uses, omit) });
                        break;
                    }
                    if (!references && omit) {
                        break;
                    }
                    requireReferences(references, fieldPath);
                    uses.push({ target, path: fieldPath });
                    fields.push({ name, kind: property.kind, value: target });
                }
                break;
            case "referenceSet": {
                const members = readMembers(value, property.modelType(), fieldPath);
                if (members.length > 0 && !omit) {
                    requireReferences(references, fieldPath);
                }
                if (references) {
                    members.forEach((target, index) => uses.push({ target, path: childPath(fieldPath, index) }));
                    fields.push({ name, kind: property.kind, value: members });
                }
                break;
            }
        }
    }
    return { instance, type, expectedType, fields };
}

function readMembers(value: unknown, type: ModelConstructor<object>, path: string): object[] {
    if (typeof value !== "object" || value === null || !(Symbol.iterator in value)) {
        throw invalid(path, "Expected a model collection");
    }
    const result: object[] = [];
    const seen = new Set<object>();
    for (const item of value as Iterable<unknown>) {
        const itemPath = childPath(path, result.length);
        const member = requireModel(item, type, itemPath);
        if (seen.has(member)) {
            throw invalid(itemPath, "Duplicate identity in model collection");
        }
        seen.add(member);
        result.push(member);
    }
    return result;
}

function encode(node: Definition, registry: SnapshotRegistry, references: boolean, ids: Map<object, number>): Encoded {
    const result: Record<string, Encoded> = Object.create(null);
    if (references && (node.type.writeType === true || node.expectedType !== node.type.runtimeType)) {
        result[typeField] = registry.outputName(node.type);
    }
    const id = ids.get(node.instance);
    if (id !== undefined) {
        result[idField] = String(id);
    }
    for (const { name, kind, value } of node.fields) {
        switch (kind) {
            case "scalar":
                result[name] = value;
                break;
            case "containment":
                result[name] = encode(value, registry, references, ids);
                break;
            case "containmentList":
                result[name] = value.map(child => encode(child, registry, references, ids));
                break;
            case "reference":
                result[name] = String(ids.get(value)!);
                break;
            case "value":
                result[name] = encode(value, registry, references, ids);
                break;
            case "referenceSet":
                result[name] = value.map(member => ids.get(member)!).sort((left, right) => left - right).map(String);
                break;
            case "scalarList":
                result[name] = value as Encoded[];
                break;
            case "referenceList":
                result[name] = value.map(member => member instanceof ImmutableValue
                    ? encode(member.definition, registry, references, ids) : String(ids.get(member)!));
                break;
        }
    }
    return result;
}

function read<T extends object>(json: string, rootType: ModelConstructor<T>, registry: SnapshotRegistry,
                                references: boolean): T {
    if (typeof json !== "string") {
        throw new TypeError("Expected a JSON string.");
    }
    const ids = new Map<string, object>();
    const pending: PendingReference[] = [];
    const root = decode(parseJson(json, references ? undefined : snapshotFields),
        rootType, "$", registry, references, ids, pending) as T;
    const resolved = pending.map(reference => resolveReference(reference, ids));
    pending.forEach((reference, index) => reference.assign(resolved[index]));
    return root;
}

function decode(node: JsonValue, expectedType: ModelConstructor<object>, path: string, registry: SnapshotRegistry,
                references: boolean, ids: Map<string, object>, pending: PendingReference[]): object {
    if (!(node instanceof Map)) {
        throw invalid(path, "Expected a VMF model object");
    }
    const discriminator = node.get(typeField);
    let type: SnapshotType;
    if (discriminator !== undefined) {
        if (typeof discriminator !== "string" || discriminator.trim() === "") {
            throw invalid(childPath(path, typeField), "Expected a non-empty VMF type name");
        }
        type = registry.typeForName(discriminator, expectedType, path);
    } else {
        type = registry.typeForClass(expectedType, path);
    }

    if (type.immutable === true) {
        return decodeImmutable(node, type, path, registry, references, ids, pending);
    }
    const result = type.create();
    if (nativeType(result, path) !== type.runtimeType) {
        throw invalid(path, `Factory for '${type.wireName}' did not create the registered concrete type`);
    }
    const idFieldValue = node.get(idField);
    if (idFieldValue !== undefined) {
        const id = readId(idFieldValue, childPath(path, idField));
        if (ids.has(id)) {
            throw invalid(path, `Duplicate ${idField} '${id}'`);
        }
        ids.set(id, result);
    }

    for (const property of type.properties) {
        const value = node.get(property.wireName);
        if (value === undefined) {
            continue;
        }
        const fieldPath = childPath(path, property.wireName);
        switch (property.kind) {
            case "scalarList":
                array(value, fieldPath).forEach((item, index) =>
                    property.add(result, readScalar(item, property, childPath(fieldPath, index))));
                break;
            case "referenceList": {
                const modelType = property.modelType();
                array(value, fieldPath).forEach((member, index) => {
                    const memberPath = childPath(fieldPath, index);
                    const assign = (target: object): void => property.add(result, target);
                    if (member instanceof Map) {
                        pending.push({ value: readImmutable(member, modelType, memberPath, registry, references, ids,
                            pending), path: memberPath, type: modelType, assign });
                    } else {
                        requireReferences(references, fieldPath);
                        pending.push({ id: readId(member, memberPath), path: memberPath, type: modelType, assign });
                    }
                });
                break;
            }
            case "scalar":
                property.set(result, readScalar(value, property, fieldPath));
                break;
            case "containment":
                property.set(result, value === null ? null
                    : decode(value, property.modelType(), fieldPath, registry, references, ids, pending));
                break;
            case "containmentList": {
                const modelType = property.modelType();
                array(value, fieldPath).forEach((member, index) => property.add(result,
                    decode(member, modelType, childPath(fieldPath, index), registry, references, ids, pending)));
                break;
            }
            case "reference":
                if (value === null) {
                    property.set(result, null);
                } else if (value instanceof Map) {
                    property.set(result, readImmutable(value, property.modelType(), fieldPath, registry, references, ids,
                        pending));
                } else {
                    requireReferences(references, fieldPath);
                    pending.push({ id: readId(value, fieldPath), path: fieldPath, type: property.modelType(),
                        assign: target => property.set(result, target) });
                }
                break;
            case "referenceSet": {
                const members = array(value, fieldPath);
                if (members.length > 0) {
                    requireReferences(references, fieldPath);
                }
                const seen = new Set<string>();
                const modelType = property.modelType();
                members.forEach((member, index) => {
                    const memberPath = childPath(fieldPath, index);
                    const id = readId(member, memberPath);
                    if (seen.has(id)) {
                        throw invalid(memberPath, `Duplicate reference identity '${id}'`);
                    }
                    seen.add(id);
                    pending.push({ id, path: memberPath, type: modelType, assign: target => property.add(result, target) });
                });
                break;
            }
        }
    }
    return result;
}

/** Reads an immutable object from its values; it has no identity and holds no containment. */
function decodeImmutable(node: Map<string, JsonValue>, type: SnapshotType, path: string, registry: SnapshotRegistry,
                         references: boolean, ids: Map<string, object>, pending: PendingReference[]): object {
    if (node.has(idField)) {
        throw invalid(childPath(path, idField), "An immutable value has no identity");
    }
    if (type.createWith === undefined) {
        throw invalid(path, `No factory for the immutable type '${type.wireName}'`);
    }
    const values: Record<string, unknown> = Object.create(null);
    for (const property of type.properties) {
        const value = node.get(property.wireName);
        if (value === undefined) {
            continue;
        }
        const fieldPath = childPath(path, property.wireName);
        const name = property.name ?? property.wireName;
        switch (property.kind) {
            case "scalar":
                values[name] = readScalar(value, property, fieldPath);
                break;
            case "scalarList":
                values[name] = array(value, fieldPath).map((item, index) =>
                    readScalar(item, property, childPath(fieldPath, index)));
                break;
            case "reference":
                values[name] = value === null ? null
                    : readImmutable(value, property.modelType(), fieldPath, registry, references, ids, pending);
                break;
            case "referenceList":
                values[name] = array(value, fieldPath).map((item, index) => readImmutable(item, property.modelType(),
                    childPath(fieldPath, index), registry, references, ids, pending));
                break;
            default:
                throw invalid(fieldPath, "An immutable value cannot hold containment");
        }
    }
    const result = type.createWith(values);
    if (nativeType(result, path) !== type.runtimeType) {
        throw invalid(path, `Factory for '${type.wireName}' did not create the registered concrete type`);
    }
    return result;
}

/** A value written in a reference position, which must be an immutable object. */
function readImmutable(node: JsonValue, expectedType: ModelConstructor<object>, path: string, registry: SnapshotRegistry,
                       references: boolean, ids: Map<string, object>, pending: PendingReference[]): object {
    const value = decode(node, expectedType, path, registry, references, ids, pending);
    if (!isImmutable(value, registry, path)) {
        throw invalid(path, "Expected a reference id or an immutable value");
    }
    return value;
}

function isImmutable(value: object, registry: SnapshotRegistry, path: string): boolean {
    return registry.typeForClass(nativeType(value, path), path).immutable === true;
}

function resolveReference(reference: PendingReference, ids: Map<string, object>): object {
    if (reference.value !== undefined) {
        return reference.value;
    }
    const target = ids.get(reference.id!);
    if (target === undefined) {
        throw invalid(reference.path, `Unresolved reference '${reference.id}'`);
    }
    if (!(target instanceof reference.type)) {
        throw invalid(reference.path,
            `Wrong target type for reference '${reference.id}'. Expected '${reference.type.name}'`);
    }
    return target;
}

function array(value: JsonValue, path: string): JsonValue[] {
    if (!Array.isArray(value)) {
        throw invalid(path, "Expected an array of model relationships");
    }
    return value;
}

function readId(value: JsonValue, path: string): string {
    if (typeof value === "string" && value.length > 0) {
        return value;
    }
    if (value instanceof JsonNumber && isIntegerToken(value.text)) {
        return value.text === "-0" ? "0" : value.text;
    }
    throw invalid(path, "Expected a non-empty string or integer VMF ID");
}

const integralRanges = { int8: [-128, 127], int16: [-32768, 32767], int32: [-2147483648, 2147483647] } as const;
const maxFloat32 = 3.4028234663852886e38;

function isIntegral(type: string): type is keyof typeof integralRanges {
    return type === "int8" || type === "int16" || type === "int32";
}

function isIntegerToken(text: string): boolean {
    return /^-?[0-9]+$/.test(text);
}

function readScalar(value: JsonValue, property: ScalarProperty, path: string): unknown {
    if (value === null && allowsNull(property)) {
        return null;
    }
    switch (property.scalarType) {
        case "object":
            return fromWire(value, path);
        case "uuid":
            if (typeof value === "string") {
                return requireScalar(value, property, path);
            }
            break;
        case "external": {
            const codec = property.codec!();
            const json = fromWire(value, path);
            try {
                return codec.read(json);
            } catch (error) {
                throw invalid(path, `Could not read the external value (${describe(error)})`);
            }
        }
        default:
            break;
    }
    if (isIntegral(property.scalarType) && value instanceof JsonNumber && isIntegerToken(value.text)) {
        return requireScalar(Number(value.text), property, path);
    }
    if ((property.scalarType === "float64" || property.scalarType === "float32") && value instanceof JsonNumber) {
        return requireScalar(Number(value.text), property, path);
    }
    if (property.scalarType === "int64" && value instanceof JsonNumber && isIntegerToken(value.text)) {
        return requireScalar(BigInt(value.text), property, path);
    }
    return requireScalar(value, property, path);
}

/** The document form of a non-null scalar value. */
function writeScalar(value: unknown, property: ScalarProperty, path: string): Encoded {
    switch (property.scalarType) {
        case "object":
            return toWire(value, path);
        case "external": {
            const codec = property.codec!();
            let json: unknown;
            try {
                json = codec.write(value);
            } catch (error) {
                throw invalid(path, `Could not write the external value (${describe(error)})`);
            }
            if (json === null || json === undefined) {
                throw invalid(path, "An external codec must write a JSON value other than null");
            }
            return toWire(json, path);
        }
        default:
            return requireScalar(value, property, path);
    }
}

function requireScalar(value: unknown, property: ScalarProperty, path: string): Scalar {
    if (property.scalarType === "string" && typeof value === "string") {
        requireValidText(value, path);
        if (property.literals !== undefined && !property.literals.includes(value)) {
            throw invalid(path, `Expected one of ${property.literals.map(literal => JSON.stringify(literal)).join(", ")}`);
        }
        return value;
    }
    if (property.scalarType === "uuid" && typeof value === "string") {
        try {
            return canonicalUuid(value)!;
        } catch {
            throw invalid(path, "Expected a UUID of 8-4-4-4-12 hexadecimal digits");
        }
    }
    if (property.scalarType === "boolean" && typeof value === "boolean") {
        return value;
    }
    if (isIntegral(property.scalarType) && typeof value === "number" && Number.isInteger(value)
        && value >= integralRanges[property.scalarType][0] && value <= integralRanges[property.scalarType][1]) {
        return value === 0 ? 0 : value;
    }
    if (property.scalarType === "float64" && typeof value === "number" && Number.isFinite(value)) {
        return value;
    }
    if (property.scalarType === "float32" && typeof value === "number" && Number.isFinite(value)
        && Math.abs(value) <= maxFloat32) {
        return value;
    }
    if (property.scalarType === "char" && typeof value === "string" && value.length === 1) {
        return value;
    }
    if (property.scalarType === "int64" && typeof value === "bigint" && value >= minInt64 && value <= maxInt64) {
        return value;
    }
    throw invalid(path, `Expected a JSON value of type '${property.scalarType}'`);
}

function allowsNull(property: ScalarProperty): boolean {
    return property.nullable ?? property.scalarType === "string";
}

function describe(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
}

function requireReferences(references: boolean, path: string): void {
    if (!references) {
        throw invalid(path, "Plain JSON cannot represent a non-owning reference");
    }
}

function requireModel(value: unknown, type: ModelConstructor<object>, path: string): object {
    if (!(value instanceof type)) {
        throw invalid(path, `Expected an instance of '${type.name}'`);
    }
    return value;
}

function nativeType(value: object, path: string): ModelConstructor<object> {
    const constructor = typeof value === "object" && value !== null ? Object.getPrototypeOf(value)?.constructor : undefined;
    if (typeof constructor !== "function") {
        throw invalid(path, "Expected a native VMF model object");
    }
    return constructor as ModelConstructor<object>;
}

/** JSON text for an encoded snapshot; 64-bit integers are written exactly. */
function stringify(value: Encoded): string {
    if (typeof value === "bigint") {
        return value.toString();
    }
    if (value instanceof JsonNumber) {
        return value.text;
    }
    if (Array.isArray(value)) {
        return "[" + value.map(stringify).join(",") + "]";
    }
    if (typeof value === "object" && value !== null) {
        return "{" + Object.entries(value).map(([key, member]) => JSON.stringify(key) + ":" + stringify(member)).join(",")
            + "}";
    }
    return JSON.stringify(value);
}

function invalid(path: string, message: string): TypeError {
    return new TypeError(`${message} at ${path}.`);
}
