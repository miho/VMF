import { childPath, JsonNumber, type JsonValue, requireValidText } from "./wire-json.js";

/**
 * A JSON value as model code sees it: what `JSON.parse` returns, except that integers beyond
 * `Number.MAX_SAFE_INTEGER` are `bigint`. Objects are plain objects with string keys.
 */
export type JsonLike = null | boolean | number | bigint | string | readonly JsonLike[] | { readonly [key: string]: JsonLike };

/**
 * How documents write and read the values of an external type (`vmf:typescript:external-type`) and how content
 * equality compares them. `write` returns any JSON value but `null`; `read` receives it back. Without `equals`
 * values are compared with `===`. Deep copies and read-only views share external values; VMF does not copy them.
 */
export interface ExternalCodec<T> {
    write(value: T): JsonLike;
    read(json: JsonLike): T;
    equals?(left: T, right: T): boolean;
    hashCode?(value: T): number;
}

/** @internal The document form of a value: a JSON tree whose numbers keep their exact text. */
export type WireValue = null | boolean | string | JsonNumber | WireValue[] | { [key: string]: WireValue };

const maxDepth = 1000;
const uuidPattern = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * A UUID in canonical form: 8-4-4-4-12 hexadecimal digits in lower case, as Java's `UUID.toString()` writes it.
 * Accepts either case; `null` stays `null`.
 */
export function canonicalUuid(value: string | null): string | null {
    if (value === null || value === undefined) {
        return null;
    }
    if (typeof value !== "string" || !uuidPattern.test(value)) {
        throw new TypeError(`'${String(value)}' is not a UUID of 8-4-4-4-12 hexadecimal digits.`);
    }
    return value.toLowerCase();
}

/** @internal An untyped value as stored: `undefined` becomes `null`. */
export function untyped(value: unknown): unknown {
    return value === undefined ? null : value;
}

/**
 * @internal The document form of an untyped value. A safe integer is written as an integer, any other number
 * with a fraction or exponent, a bigint as an integer. Rejects values without a JSON form, cycles and nesting
 * deeper than 1000.
 */
export function toWire(value: unknown, path: string): WireValue {
    return encode(value, path, 0, new Set<object>());
}

function encode(value: unknown, path: string, depth: number, active: Set<object>): WireValue {
    if (depth > maxDepth) {
        throw invalid(path, "An untyped value is nested too deeply");
    }
    switch (typeof value) {
        case "boolean":
            return value;
        case "string":
            requireValidText(value, path);
            return value;
        case "number":
            if (!Number.isFinite(value)) {
                throw invalid(path, `The number ${value} has no JSON form`);
            }
            return new JsonNumber(Number.isSafeInteger(value) ? String(value)
                : Number.isInteger(value) ? value.toExponential() : String(value));
        case "bigint":
            return new JsonNumber(value.toString());
        case "object": {
            if (value === null) {
                return null;
            }
            if (!Array.isArray(value) && !isPlainObject(value)) {
                throw invalid(path, `A ${value.constructor?.name ?? "value"} has no JSON form`);
            }
            if (active.has(value)) {
                throw invalid(path, "An untyped value cannot contain itself");
            }
            active.add(value);
            try {
                if (Array.isArray(value)) {
                    return value.map((item, index) => encode(item, childPath(path, index), depth + 1, active));
                }
                const result: { [key: string]: WireValue } = Object.create(null);
                for (const [key, member] of Object.entries(value)) {
                    requireValidText(key, path);
                    result[key] = encode(member, childPath(path, key), depth + 1, active);
                }
                return result;
            } finally {
                active.delete(value);
            }
        }
        default:
            throw invalid(path, `A ${typeof value} has no JSON form`);
    }
}

/** @internal An untyped value read from a document. */
export function fromWire(value: JsonValue, path: string): JsonLike {
    if (value instanceof JsonNumber) {
        if (/^-?[0-9]+$/.test(value.text)) {
            const integer = BigInt(value.text);
            return integer >= BigInt(Number.MIN_SAFE_INTEGER) && integer <= BigInt(Number.MAX_SAFE_INTEGER)
                ? Number(integer) : integer;
        }
        const number = Number(value.text);
        if (!Number.isFinite(number)) {
            throw invalid(path, `The number ${value.text} is out of range`);
        }
        return number;
    }
    if (Array.isArray(value)) {
        return value.map((item, index) => fromWire(item, childPath(path, index)));
    }
    if (value instanceof Map) {
        // Object.fromEntries defines own properties, also for a key such as "__proto__".
        return Object.fromEntries([...value].map(([key, member]) => [key, fromWire(member, childPath(path, key))]));
    }
    return value;
}

/** @internal Content equality of untyped values: by value for JSON trees, `===` for anything else. */
export function sameJsonLike(left: unknown, right: unknown): boolean {
    if (typeof left === "number" && typeof right === "number") {
        return Object.is(left, right);
    }
    if (left === right) {
        return true;
    }
    if (Array.isArray(left) && Array.isArray(right)) {
        return left.length === right.length && left.every((item, index) => sameJsonLike(item, right[index]));
    }
    if (isPlainObject(left) && isPlainObject(right)) {
        const keys = Object.keys(left);
        return keys.length === Object.keys(right).length
            && keys.every(key => Object.hasOwn(right, key) && sameJsonLike(left[key], right[key]));
    }
    return false;
}

/** @internal A hash code that agrees with {@link sameJsonLike}. */
export function hashJsonLike(value: unknown): number {
    switch (typeof value) {
        case "string":
            return textHash(value);
        case "boolean":
            return value ? 1231 : 1237;
        case "number":
            return Number.isInteger(value) ? value | 0 : textHash(String(value));
        case "bigint":
            return textHash(value.toString());
        case "object":
            if (Array.isArray(value)) {
                return value.reduce<number>((hash, item) => (Math.imul(hash, 31) + hashJsonLike(item)) | 0, 1);
            }
            if (isPlainObject(value)) {
                // Key order carries no meaning.
                return Object.entries(value).reduce<number>((hash, [key, member]) =>
                    (hash + (textHash(key) ^ hashJsonLike(member))) | 0, 7);
            }
            return 0;
        default:
            return 0;
    }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
        return false;
    }
    const prototype = Object.getPrototypeOf(value);
    return prototype === Object.prototype || prototype === null;
}

function textHash(text: string): number {
    let hash = 0;
    for (let index = 0; index < text.length; index++) {
        hash = (Math.imul(hash, 31) + text.charCodeAt(index)) | 0;
    }
    return hash;
}

function invalid(path: string, message: string): TypeError {
    return new TypeError(`${message} at ${path}.`);
}
