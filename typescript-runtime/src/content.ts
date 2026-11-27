import { sameValue, ScalarList } from "./collections.js";
import { hashJsonLike, sameJsonLike } from "./json-values.js";
import { ReferenceList, ReferenceSet, ReferenceSlot } from "./relationships.js";
import { type ModelConstructor, SnapshotRegistry, type SnapshotProperty, type SnapshotType } from "./snapshot-registry.js";
import { type MapView, unwrapReadOnly } from "./views.js";

/**
 * Content equality: same concrete type, equal scalar values, scalar lists in order, scalar sets and maps in any
 * order, and content-equal contained children (in order for lists, in any order for sets). References and the
 * container are not compared.
 */
export function contentEquals(left: unknown, right: unknown, registry: SnapshotRegistry): boolean {
    left = unwrapReadOnly(left);
    right = unwrapReadOnly(right);
    if (left === right) {
        return true;
    }
    if (!isObject(left) || !isObject(right) || Object.getPrototypeOf(left) !== Object.getPrototypeOf(right)) {
        return false;
    }
    for (const property of typeOf(left, registry).properties) {
        const a = property.get(left);
        const b = property.get(right);
        switch (property.kind) {
            case "scalar":
                if (!sameScalar(property, a, b)) {
                    return false;
                }
                break;
            case "scalarList":
                if (!sameItems(a, b, (x, y) => sameScalar(property, x, y))) {
                    return false;
                }
                break;
            case "scalarSet":
                if (!sameMembers(a, b, sameValue)) {
                    return false;
                }
                break;
            case "scalarMap":
                if (!sameEntries(a as MapView<unknown, unknown>, b as MapView<unknown, unknown>)) {
                    return false;
                }
                break;
            case "containment":
                if (!contentEquals(a ?? null, b ?? null, registry)) {
                    return false;
                }
                break;
            case "containmentList":
                if (!sameItems(a, b, (x, y) => contentEquals(x, y, registry))) {
                    return false;
                }
                break;
            case "containmentSet":
                if (!sameMembers(a, b, (x, y) => contentEquals(x, y, registry))) {
                    return false;
                }
                break;
            default:
                break;
        }
    }
    return true;
}

/** A hash code that agrees with {@link contentEquals}. */
export function contentHashCode(model: object, registry: SnapshotRegistry): number {
    const type = typeOf(model, registry);
    let hash = textHash(type.wireName);
    for (const property of type.properties) {
        const value = property.get(model);
        switch (property.kind) {
            case "scalar":
                hash = mix(hash, valueHash(property, value));
                break;
            case "scalarList":
                for (const item of value as Iterable<unknown>) {
                    hash = mix(hash, valueHash(property, item));
                }
                break;
            case "scalarSet":
                hash = mix(hash, unorderedHash(value as Iterable<unknown>, scalarHash));
                break;
            case "scalarMap":
                hash = mix(hash, unorderedHash(value as Iterable<[unknown, unknown]>,
                    ([key, entry]) => scalarHash(key) ^ scalarHash(entry)));
                break;
            case "containment":
                hash = mix(hash, value === null || value === undefined ? 0 : contentHashCode(value as object, registry));
                break;
            case "containmentList":
                for (const child of value as Iterable<object>) {
                    hash = mix(hash, contentHashCode(child, registry));
                }
                break;
            case "containmentSet":
                hash = mix(hash, unorderedHash(value as Iterable<object>, child => contentHashCode(child, registry)));
                break;
            default:
                break;
        }
    }
    return hash;
}

/**
 * Copies the root and every object reachable from it through containment and references, but not through
 * container properties. References between copied objects point to the copies; the copied root has no container.
 * Immutable values are shared, not copied.
 */
export function deepCopy<T extends object>(root: T, registry: SnapshotRegistry): T {
    const copies = new Map<object, object>();
    const originals: object[] = [];
    const visit = (model: object): void => {
        if (!copies.has(model)) {
            const type = typeOf(model, registry);
            if (type.immutable === true) {
                copies.set(model, model);
                return;
            }
            copies.set(model, type.create());
            originals.push(model);
        }
    };
    visit(root);
    for (let index = 0; index < originals.length; index++) {
        const original = originals[index];
        for (const property of typeOf(original, registry).properties) {
            const value = property.get(original);
            switch (property.kind) {
                case "containment":
                case "reference":
                    if (isObject(value)) {
                        visit(value);
                    }
                    break;
                case "containmentList":
                case "containmentSet":
                case "referenceList":
                case "referenceSet":
                    for (const item of value as Iterable<object>) {
                        visit(item);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    // Values and plain references first, so that keyed containments receive entries with their keys; then
    // containment; then references with opposites, whose ends are all copied.
    const copyOf = (model: unknown): object | null => isObject(model) ? copies.get(model)! : null;
    for (const stage of [copyValues, copyContainment, copyReferences]) {
        for (const original of originals) {
            const copy = copies.get(original)!;
            for (const property of typeOf(original, registry).properties) {
                stage(property, property.get(original), copy, copyOf);
            }
        }
    }
    return copies.get(root) as T;
}

type CopyOf = (model: unknown) => object | null;

function copyValues(property: SnapshotProperty, value: unknown, copy: object, copyOf: CopyOf): void {
    switch (property.kind) {
        case "scalar":
            property.set(copy, value);
            break;
        case "scalarList": {
            const target = property.get(copy) as ScalarList<unknown>;
            for (const item of value as Iterable<unknown>) {
                target.add(item);
            }
            break;
        }
        case "scalarSet":
            for (const item of value as Iterable<unknown>) {
                property.add(copy, item);
            }
            break;
        case "scalarMap":
            for (const [key, entry] of value as Iterable<[unknown, unknown]>) {
                property.put(copy, key, entry);
            }
            break;
        case "reference":
            if (property.opposite === undefined) {
                property.set(copy, copyOf(value));
            }
            break;
        default:
            break;
    }
}

function copyContainment(property: SnapshotProperty, value: unknown, copy: object, copyOf: CopyOf): void {
    switch (property.kind) {
        case "containment":
            property.set(copy, copyOf(value));
            break;
        case "containmentList":
        case "containmentSet":
            for (const item of value as Iterable<object>) {
                property.add(copy, copyOf(item)!);
            }
            break;
        default:
            break;
    }
}

function copyReferences(property: SnapshotProperty, value: unknown, copy: object, copyOf: CopyOf): void {
    switch (property.kind) {
        case "reference":
            if (property.opposite !== undefined) {
                // Both ends are copied, so copy the stored value without updating the opposite again.
                const slot = (copy as Record<string, unknown>)["__vmfRef_" + (property.name ?? property.wireName)];
                (slot as ReferenceSlot<object>).__vmfInitialize(copyOf(value));
            }
            break;
        case "referenceSet":
            for (const item of value as Iterable<object>) {
                if (property.opposite === undefined) {
                    property.add(copy, copyOf(item)!);
                } else {
                    (property.get(copy) as ReferenceSet<object>).__vmfAppendRaw(copyOf(item)!);
                }
            }
            break;
        case "referenceList": {
            const target = property.get(copy) as ReferenceList<object>;
            for (const item of value as Iterable<object>) {
                target.__vmfAppendRaw(copyOf(item)!);
            }
            break;
        }
        default:
            break;
    }
}

function typeOf(model: object, registry: SnapshotRegistry): SnapshotType {
    const constructor = Object.getPrototypeOf(model)?.constructor as ModelConstructor<object> | undefined;
    if (typeof constructor !== "function") {
        throw new TypeError("Expected a VMF model object.");
    }
    return registry.typeForClass(constructor, "$");
}

function isObject(value: unknown): value is object {
    return typeof value === "object" && value !== null;
}

function sameItems(left: unknown, right: unknown, same: (a: unknown, b: unknown) => boolean): boolean {
    const a = [...(left as Iterable<unknown>)];
    const b = [...(right as Iterable<unknown>)];
    return a.length === b.length && a.every((item, index) => same(item, b[index]));
}

type ScalarProperty = Extract<SnapshotProperty, { kind: "scalar" | "scalarList" | "scalarSet" }>;

/** Scalars by value; untyped values as JSON trees; external values by their codec's equality, else `===`. */
function sameScalar(property: ScalarProperty, left: unknown, right: unknown): boolean {
    left = left ?? null;
    right = right ?? null;
    if (property.scalarType === "object") {
        return sameJsonLike(left, right);
    }
    if (left === right) {
        return true;
    }
    if (property.scalarType === "external" && left !== null && right !== null) {
        return property.codec!().equals?.(left, right) ?? false;
    }
    return false;
}

function valueHash(property: ScalarProperty, value: unknown): number {
    if (property.scalarType === "object") {
        return hashJsonLike(value);
    }
    if (property.scalarType === "external" && value !== null && value !== undefined) {
        const codec = property.codec!();
        if (codec.hashCode !== undefined) {
            return codec.hashCode(value) | 0;
        }
        // Without a hash, values that the codec's equality relates must not hash differently.
        return codec.equals !== undefined || typeof value === "object" ? 0 : scalarHash(value);
    }
    return scalarHash(value);
}

/** The same members in any order: each member of one matches a distinct member of the other. */
function sameMembers(left: unknown, right: unknown, same: (a: unknown, b: unknown) => boolean): boolean {
    const a = [...(left as Iterable<unknown>)];
    const remaining = [...(right as Iterable<unknown>)];
    if (a.length !== remaining.length) {
        return false;
    }
    for (const item of a) {
        const match = remaining.findIndex(candidate => same(item, candidate));
        if (match < 0) {
            return false;
        }
        remaining.splice(match, 1);
    }
    return true;
}

/** The same keys with the same values (`===`), in any order. */
function sameEntries(left: MapView<unknown, unknown>, right: MapView<unknown, unknown>): boolean {
    if (left.count !== right.count) {
        return false;
    }
    for (const [key, value] of left) {
        if (!right.has(key) || right.get(key) !== value) {
            return false;
        }
    }
    return true;
}

function unorderedHash<T>(items: Iterable<T>, hash: (item: T) => number): number {
    let sum = 0;
    for (const item of items) {
        sum = (sum + hash(item)) | 0;
    }
    return sum;
}

function scalarHash(value: unknown): number {
    switch (typeof value) {
        case "string":
            return textHash(value);
        case "boolean":
            return value ? 1231 : 1237;
        case "number":
            return Number.isInteger(value) ? value | 0 : textHash(String(value));
        case "bigint":
            return textHash(value.toString());
        default:
            return 0;
    }
}

function textHash(text: string): number {
    let hash = 0;
    for (let index = 0; index < text.length; index++) {
        hash = (Math.imul(hash, 31) + text.charCodeAt(index)) | 0;
    }
    return hash;
}

function mix(hash: number, value: number): number {
    return (Math.imul(hash, 31) + value) | 0;
}
