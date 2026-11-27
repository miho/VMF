// eslint-disable-next-line @typescript-eslint/no-unsafe-function-type
type AnyClass = Function;

/**
 * @internal The host code that a generated model module needs: external value codecs, bound default values and
 * behaviors. The module's `installModelBindings` function installs it; model code asks for a binding when it first
 * needs it, so a model can be used without bindings as long as it does not reach one.
 */
export class ModelBindingSlot<B extends object> {
    #installed: B | undefined;

    constructor(readonly model: string, readonly required: readonly string[]) {
    }

    /** Checks that every required binding is present and installs the bindings, replacing earlier ones. */
    install(bindings: B): void {
        if (typeof bindings !== "object" || bindings === null) {
            throw new TypeError(`installModelBindings() of ${this.model} expects a bindings object.`);
        }
        const missing = this.required.filter(path => !isBinding(path, member(bindings, path)));
        if (missing.length > 0) {
            throw new TypeError(`installModelBindings() of ${this.model} is missing ${missing.join(", ")}.`);
        }
        this.#installed = bindings;
    }

    /** The binding at the path, chosen by `select` from the installed bindings. */
    use<T>(path: string, select: (bindings: B) => T): T {
        if (this.#installed === undefined) {
            throw new Error(`${this.model} needs the binding '${path}': call installModelBindings() of its generated`
                + " module first.");
        }
        return select(this.#installed);
    }
}

function member(bindings: object, path: string): unknown {
    let current: unknown = bindings;
    for (const name of path.split(".")) {
        if (typeof current !== "object" || current === null) {
            return undefined;
        }
        current = (current as Record<string, unknown>)[name];
    }
    return current;
}

function isBinding(path: string, value: unknown): boolean {
    if (path.startsWith("externals.")) {
        const codec = value as { write?: unknown; read?: unknown } | null | undefined;
        return typeof codec === "object" && codec !== null && typeof codec.write === "function"
            && typeof codec.read === "function";
    }
    return typeof value === "function";
}

const replacements = new WeakMap<object, object>();
const defaults = new WeakMap<object, Map<string, object>>();
const behaviorMethods = new WeakMap<AnyClass, readonly string[]>();

/** @internal Records the delegated methods of a model class, which a replacement behavior must implement. */
export function declareBehavior(type: AnyClass, methods: readonly string[]): void {
    behaviorMethods.set(type, Object.freeze([...methods]));
}

/**
 * @internal The behavior that handles a delegated call: the object's replacement if it has one, else its default
 * behavior for the type that declares the method.
 */
export function behaviorOf<T extends object>(model: object, declaringType: string, create: () => T): T {
    const replacement = replacements.get(model);
    return replacement !== undefined ? replacement as T : defaultBehaviorOf(model, declaringType, create);
}

/**
 * @internal The default behavior of an object for a declaring type. The binding creates it when the object first
 * needs it (its construction hook or first delegated call); each object has its own.
 */
export function defaultBehaviorOf<T extends object>(model: object, declaringType: string, create: () => T): T {
    let behaviors = defaults.get(model);
    if (behaviors === undefined) {
        behaviors = new Map<string, object>();
        defaults.set(model, behaviors);
    }
    let behavior = behaviors.get(declaringType);
    if (behavior === undefined) {
        behavior = create();
        if (typeof behavior !== "object" || behavior === null) {
            throw new TypeError(`The behavior binding of ${declaringType} returned no object.`);
        }
        behaviors.set(declaringType, behavior);
    }
    return behavior as T;
}

/**
 * @internal Replaces the behavior of one object: its delegated calls go to the replacement from now on. The
 * replacement must implement every delegated method of the object's class; it never receives construction hooks.
 */
export function replaceBehavior(model: object, behavior: object): void {
    if (typeof behavior !== "object" || behavior === null) {
        throw new TypeError("A replacement behavior must be an object.");
    }
    const missing = methodsOf(model).filter(name => typeof (behavior as Record<string, unknown>)[name] !== "function");
    if (missing.length > 0) {
        throw new TypeError(`The replacement behavior lacks ${missing.join(", ")}.`);
    }
    replacements.set(model, behavior);
}

/**
 * @internal The default behavior of a method that an interface-only type declares without a binding. A subclass
 * implements the method with its own binding; without one, only a replacement behavior can answer the call.
 */
export function unboundBehavior(method: string): () => never {
    return () => {
        throw new Error(`No default TypeScript behavior is bound for ${method}.`);
    };
}

function methodsOf(model: object): readonly string[] {
    for (let prototype = Object.getPrototypeOf(model); prototype !== null; prototype = Object.getPrototypeOf(prototype)) {
        const methods = behaviorMethods.get(prototype.constructor);
        if (methods !== undefined) {
            return methods;
        }
    }
    return [];
}
