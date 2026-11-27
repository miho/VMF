export const typeField = "@vmf-type";
export const idField = "@vmf-id";

export type ModelConstructor<T extends object> = abstract new (...args: never[]) => T;

interface SnapshotMember {
    readonly wireName: string;
    readonly get: (owner: object) => unknown;
}

interface SnapshotScalar extends SnapshotMember {
    readonly kind: "scalar";
    readonly scalarType: "string" | "boolean" | "int32";
    readonly nullable?: boolean;
    readonly set: (owner: object, value: unknown) => void;
}

interface SnapshotSingle extends SnapshotMember {
    readonly kind: "containment" | "reference";
    readonly modelType: () => ModelConstructor<object>;
    readonly set: (owner: object, value: unknown) => void;
}

interface SnapshotCollection extends SnapshotMember {
    readonly kind: "containmentList" | "referenceSet";
    readonly modelType: () => ModelConstructor<object>;
    readonly add: (owner: object, value: object) => void;
}

export type SnapshotProperty = SnapshotScalar | SnapshotSingle | SnapshotCollection;

/** The explicit native factory and wire properties of a closed, concrete model type. */
export interface SnapshotType {
    readonly wireName: string;
    readonly runtimeType: ModelConstructor<object>;
    readonly create: () => object;
    readonly properties: readonly SnapshotProperty[];
    readonly writeType?: boolean;
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
