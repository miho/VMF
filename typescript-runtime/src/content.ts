import { ScalarList } from "./collections.js";
import { ReferenceList, ReferenceSlot } from "./relationships.js";
import { type ModelConstructor, SnapshotRegistry, type SnapshotType } from "./snapshot-registry.js";

/**
 * Content equality: same concrete type, equal scalar values and scalar lists, and content-equal contained children
 * in the same order. References and the container are not compared.
 */
export function contentEquals(left: unknown, right: unknown, registry: SnapshotRegistry): boolean {
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
                if (a !== b) {
                    return false;
                }
                break;
            case "scalarList":
                if (!sameItems(a, b, (x, y) => x === y)) {
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
                hash = mix(hash, scalarHash(value));
                break;
            case "scalarList":
                for (const item of value as Iterable<unknown>) {
                    hash = mix(hash, scalarHash(item));
                }
                break;
            case "containment":
                hash = mix(hash, value === null || value === undefined ? 0 : contentHashCode(value as object, registry));
                break;
            case "containmentList":
                for (const child of value as Iterable<object>) {
                    hash = mix(hash, contentHashCode(child, registry));
                }
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
 */
export function deepCopy<T extends object>(root: T, registry: SnapshotRegistry): T {
    const copies = new Map<object, object>();
    const originals: object[] = [];
    const visit = (model: object): void => {
        if (!copies.has(model)) {
            copies.set(model, typeOf(model, registry).create());
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

    const copyOf = (model: unknown): object | null => isObject(model) ? copies.get(model)! : null;
    for (const original of originals) {
        const copy = copies.get(original)!;
        for (const property of typeOf(original, registry).properties) {
            const value = property.get(original);
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
                case "containment":
                    property.set(copy, copyOf(value));
                    break;
                case "containmentList":
                case "referenceSet":
                    for (const item of value as Iterable<object>) {
                        property.add(copy, copyOf(item)!);
                    }
                    break;
                case "reference":
                    if (property.opposite === undefined) {
                        property.set(copy, copyOf(value));
                    } else {
                        // Both ends are copied, so copy the stored value without updating the opposite again.
                        const slot = (copy as Record<string, unknown>)["__vmfRef_" + (property.name ?? property.wireName)];
                        (slot as ReferenceSlot<object>).value = copyOf(value);
                    }
                    break;
                case "referenceList": {
                    const target = property.get(copy) as ReferenceList<object>;
                    for (const item of value as Iterable<object>) {
                        target.__vmfAppendRaw(copyOf(item)!);
                    }
                    break;
                }
            }
        }
    }
    return copies.get(root) as T;
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
