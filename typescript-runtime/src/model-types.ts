// eslint-disable-next-line @typescript-eslint/no-unsafe-function-type
type AnyClass = Function;

const declared = new WeakMap<AnyClass, readonly AnyClass[]>();

/** Records the model supertypes a class implements besides its base class, for {@link isInstance}. */
export function declareSupertypes(type: AnyClass, supertypes: readonly AnyClass[]): void {
    declared.set(type, supertypes);
}

/** `instanceof` for model types: the prototype chain, or a supertype declared for a class in that chain. */
export function isInstance(value: unknown, type: AnyClass): boolean {
    if (typeof value !== "object" || value === null) {
        return false;
    }
    if (Function.prototype[Symbol.hasInstance].call(type, value)) {
        return true;
    }
    for (let prototype = Object.getPrototypeOf(value); prototype !== null; prototype = Object.getPrototypeOf(prototype)) {
        if (declared.get(prototype.constructor)?.includes(type)) {
            return true;
        }
    }
    return false;
}
