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
 */
export type ScalarType = "string" | "boolean" | "int8" | "int16" | "int32" | "int64" | "float32" | "float64" | "char";

interface SnapshotScalar extends SnapshotMember {
    readonly kind: "scalar";
    readonly scalarType: ScalarType;
    readonly nullable?: boolean;
    readonly set: (owner: object, value: unknown) => void;
}

interface SnapshotScalarList extends SnapshotMember {
    readonly kind: "scalarList";
    readonly scalarType: ScalarType;
    /** True if the list may hold null elements. */
    readonly nullable?: boolean;
    readonly add: (owner: object, value: unknown) => void;
}

interface SnapshotSingle extends SnapshotMember {
    readonly kind: "containment" | "reference";
    readonly modelType: () => ModelConstructor<object>;
    readonly set: (owner: object, value: unknown) => void;
    /** The opposite property of a reference that keeps both ends consistent. */
    readonly opposite?: string;
}

interface SnapshotCollection extends SnapshotMember {
    readonly kind: "containmentList" | "referenceSet" | "referenceList";
    readonly modelType: () => ModelConstructor<object>;
    readonly add: (owner: object, value: object) => void;
    readonly opposite?: string;
}

export type SnapshotProperty = SnapshotScalar | SnapshotScalarList | SnapshotSingle | SnapshotCollection;

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
        if (type.runtimeType !== expectedType && !(type.runtimeType.prototype instanceof expectedType)) {
            throw new TypeError(`VMF type '${wireName}' is not compatible with '${expectedType.name}' at ${path}.`);
        }
        return type;
    }

    outputName(type: SnapshotType): string {
        return this.#outputAliases.get(type.wireName) ?? type.wireName;
    }
}
