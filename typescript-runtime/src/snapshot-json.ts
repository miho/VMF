import {
    idField,
    type ModelConstructor,
    SnapshotRegistry,
    type SnapshotProperty,
    type SnapshotType,
    typeField,
} from "./snapshot-registry.js";
import { childPath, JsonNumber, type JsonValue, parseJson, requireValidText } from "./wire-json.js";

type ScalarProperty = Extract<SnapshotProperty, { kind: "scalar" }>;
type Scalar = string | number | boolean;
type Encoded = Scalar | Encoded[] | { [key: string]: Encoded };
const snapshotFields = new Set([typeField, idField]);

interface Definition {
    readonly instance: object;
    readonly type: SnapshotType;
    readonly expectedType: ModelConstructor<object>;
    readonly fields: readonly Field[];
}

type Field =
    | { name: string; kind: "scalar"; value: Scalar }
    | { name: string; kind: "containment"; value: Definition }
    | { name: string; kind: "containmentList"; value: Definition[] }
    | { name: string; kind: "reference"; value: object }
    | { name: string; kind: "referenceSet"; value: object[] };

interface ReferenceUse {
    readonly target: object;
    readonly path: string;
}

interface PendingReference {
    readonly id: string;
    readonly path: string;
    readonly type: ModelConstructor<object>;
    readonly assign: (target: object) => void;
}

/** Reads and writes VMF snapshots using only the explicitly registered model types. */
export class SnapshotJson {
    static write(root: object, registry: SnapshotRegistry): string {
        return write(root, nativeType(root, "$"), registry, true);
    }

    static read<T extends object>(json: string, rootType: ModelConstructor<T>, registry: SnapshotRegistry): T {
        return read(json, rootType, registry, true);
    }

    /** Writes a typed document with no VMF metadata. References and ambiguous types are rejected. */
    static writePlain<T extends object>(root: T, rootType: ModelConstructor<T>, registry: SnapshotRegistry): string {
        return write(root, rootType, registry, false);
    }

    static readPlain<T extends object>(json: string, rootType: ModelConstructor<T>, registry: SnapshotRegistry): T {
        return read(json, rootType, registry, false);
    }
}

function write(root: object, rootType: ModelConstructor<object>, registry: SnapshotRegistry, references: boolean): string {
    const contained = new Set<object>();
    const uses: ReferenceUse[] = [];
    const definition = capture(root, rootType, "$", registry, references, contained, uses);
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
    return JSON.stringify(encode(definition, registry, references, ids));
}

function capture(instance: object, expectedType: ModelConstructor<object>, path: string, registry: SnapshotRegistry,
                 references: boolean, contained: Set<object>, uses: ReferenceUse[]): Definition {
    const actualType = nativeType(instance, path);
    const type = registry.typeForClass(actualType, path);
    requireModel(instance, expectedType, path);
    if (!references && actualType !== expectedType) {
        throw invalid(path, `Plain JSON requires a discriminator to restore '${type.wireName}' as '${expectedType.name}'`);
    }
    if (contained.has(instance)) {
        throw invalid(path, "Object occurs more than once in the containment tree");
    }
    contained.add(instance);

    const fields: Field[] = [];
    for (const property of type.properties) {
        const name = property.wireName;
        const fieldPath = childPath(path, name);
        const value = property.get(instance);
        switch (property.kind) {
            case "scalar":
                if (value === null || value === undefined) {
                    if (!allowsNull(property)) {
                        throw invalid(fieldPath, `Expected a JSON value of type '${property.scalarType}'`);
                    }
                } else {
                    fields.push({ name, kind: property.kind, value: requireScalar(value, property, fieldPath) });
                }
                break;
            case "containment":
                if (value !== null && value !== undefined) {
                    const modelType = property.modelType();
                    fields.push({ name, kind: property.kind, value: capture(requireModel(value, modelType, fieldPath),
                        modelType, fieldPath, registry, references, contained, uses) });
                }
                break;
            case "containmentList": {
                const modelType = property.modelType();
                const children = readMembers(value, modelType, fieldPath).map((member, index) =>
                    capture(member, modelType, childPath(fieldPath, index), registry, references, contained, uses));
                fields.push({ name, kind: property.kind, value: children });
                break;
            }
            case "reference":
                if (value !== null && value !== undefined) {
                    requireReferences(references, fieldPath);
                    const target = requireModel(value, property.modelType(), fieldPath);
                    uses.push({ target, path: fieldPath });
                    fields.push({ name, kind: property.kind, value: target });
                }
                break;
            case "referenceSet": {
                const members = readMembers(value, property.modelType(), fieldPath);
                if (members.length > 0) {
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
            case "referenceSet":
                result[name] = value.map(member => ids.get(member)!).sort((left, right) => left - right).map(String);
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

function resolveReference(reference: PendingReference, ids: Map<string, object>): object {
    const target = ids.get(reference.id);
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

function isIntegerToken(text: string): boolean {
    return /^-?[0-9]+$/.test(text);
}

function readScalar(value: JsonValue, property: ScalarProperty, path: string): Scalar | null {
    if (value === null && allowsNull(property)) {
        return null;
    }
    if (property.scalarType === "int32" && value instanceof JsonNumber && isIntegerToken(value.text)) {
        return requireScalar(Number(value.text), property, path);
    }
    return requireScalar(value, property, path);
}

function requireScalar(value: unknown, property: ScalarProperty, path: string): Scalar {
    if (property.scalarType === "string" && typeof value === "string") {
        requireValidText(value, path);
        return value;
    }
    if (property.scalarType === "boolean" && typeof value === "boolean") {
        return value;
    }
    if (property.scalarType === "int32" && typeof value === "number" && Number.isInteger(value)
        && value >= -2147483648 && value <= 2147483647) {
        return value === 0 ? 0 : value;
    }
    throw invalid(path, `Expected a JSON value of type '${property.scalarType}'`);
}

function allowsNull(property: ScalarProperty): boolean {
    return property.nullable ?? property.scalarType === "string";
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

function invalid(path: string, message: string): TypeError {
    return new TypeError(`${message} at ${path}.`);
}
