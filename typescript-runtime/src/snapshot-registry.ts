import type { ExternalCodec } from "./json-values.js";
import { isSubtype } from "./model-types.js";

export const typeField = "@vmf-type";
export const idField = "@vmf-id";

export type ModelConstructor<T extends object> = abstract new (...args: never[]) => T;

interface SnapshotMember {
    readonly wireName: string;
    /** The model property name; defaults to the wire name. */
    readonly name?: string;
    readonly get: (owner: object) => unknown;
}

/**
 * The scalar types of the portable contract. byte (int8), short (int16) and float (float32) are numbers and char
 * a string of one UTF-16 code unit (rule T6); documents keep them within the range of the narrower type.
 * External values: "uuid" is a canonical UUID string, "object" an untyped JSON-like value and "external" a host
 * value written and read by its codec.
 */
export type ScalarType = "string" | "boolean" | "int8" | "int16" | "int32" | "int64" | "float32" | "float64" | "char"
    | "uuid" | "object" | "external";

/** The value semantics of a scalar property beyond its scalar type. */
interface ScalarValues {
    /** The allowed values of an enum-like string type (a union of string literals). */
    readonly literals?: readonly string[];
    /** The codec of an "external" scalar type, from the installed model bindings. */
    readonly codec?: () => ExternalCodec<unknown>;
}

interface SnapshotScalar extends SnapshotMember, ScalarValues {
    readonly kind: "scalar";
    readonly scalarType: ScalarType;
    readonly nullable?: boolean;
    /** Writes `null` instead of leaving the field out, so that readers do not restore a non-null declared default. */
    readonly writeNull?: boolean;
    readonly set: (owner: object, value: unknown) => void;
}

/** A list (`scalarList`, ordered, an ordered set too) or a set (`scalarSet`) of scalar values. */
interface SnapshotScalarList extends SnapshotMember, ScalarValues {
    readonly kind: "scalarList" | "scalarSet";
    readonly scalarType: ScalarType;
    /** True if the list may hold null elements. Sets never do. */
    readonly nullable?: boolean;
    readonly add: (owner: object, value: unknown) => void;
}

/** A map of scalar keys to scalar values that may be null. */
interface SnapshotScalarMap extends SnapshotMember {
    readonly kind: "scalarMap";
    /** The type of the keys, which are never null. */
    readonly keyType: ScalarType;
    /** The type of the values. */
    readonly scalarType: ScalarType;
    readonly nullable?: boolean;
    readonly put: (owner: object, key: unknown, value: unknown) => void;
}

interface SnapshotSingle extends SnapshotMember {
    readonly kind: "containment" | "reference";
    readonly modelType: () => ModelConstructor<object>;
    readonly set: (owner: object, value: unknown) => void;
    /** The opposite property of a reference that keeps both ends consistent. */
    readonly opposite?: string;
}

interface SnapshotCollection extends SnapshotMember {
    readonly kind: "containmentList" | "containmentSet" | "referenceSet" | "referenceList";
    readonly modelType: () => ModelConstructor<object>;
    readonly add: (owner: object, value: object) => void;
    readonly opposite?: string;
    /** A keyed containment keyed by a model reference: readers add its entries once references are resolved. */
    readonly keyedByReference?: boolean;
}

export type SnapshotProperty = SnapshotScalar | SnapshotScalarList | SnapshotScalarMap | SnapshotSingle
    | SnapshotCollection;

/** The explicit native factory and wire properties of a closed, concrete model type. */
export interface SnapshotType {
    readonly wireName: string;
    readonly runtimeType: ModelConstructor<object>;
    readonly create: () => object;
    readonly properties: readonly SnapshotProperty[];
    readonly writeType?: boolean;
    /** True for an `@Immutable` type: its objects are values, created with all their properties. */
    readonly immutable?: boolean;
    /** Creates an immutable object from property values by model property name. */
    readonly createWith?: (values: Readonly<Record<string, unknown>>) => object;
}

/** An allowlist of concrete model types and optional aliases independent of native class names. */
export class SnapshotRegistry {
    readonly #typesByName = new Map<string, SnapshotType>();
    readonly #typesByClass = new Map<ModelConstructor<object>, SnapshotType>();
    readonly #outputAliases = new Map<string, string>();

    constructor(types: readonly SnapshotType[], aliases: Readonly<Record<string, string>> = {}) {
        for (const source of types) {
            if (source.wireName.trim() === ""
                || this.#typesByName.has(source.wireName)
                || this.#typesByClass.has(source.runtimeType)) {
                throw new TypeError(`Invalid or duplicate registered VMF type '${source.wireName}'.`);
            }
            const names = new Set<string>();
            const type = Object.freeze({
                ...source,
                properties: Object.freeze(source.properties.map(property => {
                    const name = property.wireName;
                    if (name.trim() === "" || name === typeField || name === idField || names.has(name)) {
                        throw new TypeError(`Invalid or duplicate VMF field '${source.wireName}.${name}'.`);
                    }
                    names.add(name);
                    return Object.freeze({ ...property });
                })),
            });
            this.#typesByName.set(type.wireName, type);
            this.#typesByClass.set(type.runtimeType, type);
        }
        for (const [alias, canonical] of Object.entries(aliases)) {
            const type = this.#typesByName.get(canonical);
            if (type === undefined || type.wireName !== canonical) {
                throw new TypeError(`Unknown VMF type '${canonical}' for alias '${alias}'.`);
            }
            if (alias === canonical) {
                continue;
            }
            if (alias.trim() === "" || this.#typesByName.has(alias) || this.#outputAliases.has(canonical)) {
                throw new TypeError(`Conflicting VMF type alias '${alias}' for '${canonical}'.`);
            }
            this.#typesByName.set(alias, type);
            this.#outputAliases.set(canonical, alias);
        }
    }

    /** The registered concrete types in registration order. */
    get types(): readonly SnapshotType[] {
        return [...this.#typesByClass.values()];
    }

    typeForClass(runtimeType: ModelConstructor<object>, path: string): SnapshotType {
        const type = this.#typesByClass.get(runtimeType);
        if (type === undefined) {
            throw new TypeError(`Unregistered VMF class '${runtimeType.name}' at ${path}.`);
        }
        return type;
    }

    typeForName(wireName: string, expectedType: ModelConstructor<object>, path: string): SnapshotType {
        const type = this.#typesByName.get(wireName);
        if (type === undefined) {
            throw new TypeError(`Unregistered VMF type '${wireName}' at ${path}.`);
        }
        // Implemented supertypes count too: a class implements every model supertype besides its base class.
        if (!isSubtype(type.runtimeType, expectedType)) {
            throw new TypeError(`VMF type '${wireName}' is not compatible with '${expectedType.name}' at ${path}.`);
        }
        return type;
    }

    outputName(type: SnapshotType): string {
        return this.#outputAliases.get(type.wireName) ?? type.wireName;
    }
}
