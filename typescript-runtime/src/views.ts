import { changesOf, type ListenerScope, type ModelChange, type ModelChangeListener } from "./changes.js";
import { requireCapacity, sameValue, ScalarList, ScalarMap, ScalarSet } from "./collections.js";
import { __vmfLookupsOf, __vmfUseViews, findModelType, type ModelType, modelTypeOf } from "./reflection.js";
import { ContainmentList, ContainmentSet, ReferenceList, ReferenceSet } from "./relationships.js";

/** The read-only type of a model type (what its `asReadOnly()` returns); other types stay as they are. */
export type ReadOnlyOf<T> = T extends { asReadOnly(): infer R } ? R : T;

/** Read access to the elements of a model collection. */
export interface CollectionView<T> extends Iterable<T> {
    readonly count: number;
    contains(value: T): boolean;
}

/** Read access to the elements of an ordered model list. */
export interface ListView<T> extends CollectionView<T> {
    at(index: number): T;
    indexOf(value: T): number;
}

/** Read access to the entries of a map property, in the order of their keys. */
export interface MapView<K, V> extends Iterable<[K, V]> {
    readonly count: number;
    has(key: K): boolean;
    /** The value of the key, or undefined if the map has no such key. */
    get(key: K): V | undefined;
    keys(): IterableIterator<K>;
    values(): IterableIterator<V>;
    entries(): IterableIterator<[K, V]>;
}

/** How the values of an immutable collection are checked when it is created. */
export interface ValueOptions {
    /** Rejects a value that occurs more than once (an ordered set). */
    readonly unique?: boolean;
    /** The most elements the collection may hold; -1, the default, is unbounded. */
    readonly upperBound?: number;
    /** False rejects null elements. */
    readonly nullable?: boolean;
}

/**
 * An ordered list that cannot be changed through this object: the list of an immutable value, or the read-only
 * view of a model list, which shows the list's current elements.
 */
export class ReadOnlyList<T> implements ListView<T> {
    readonly #source: ListView<unknown>;
    readonly #project: (value: unknown) => T;

    private constructor(source: ListView<unknown>, project: (value: unknown) => T) {
        this.#source = source;
        this.#project = project;
    }

    /** A list of the given values that never changes; `options` check the values first. */
    static of<T>(values: Iterable<T>, options: ValueOptions = {}): ReadOnlyList<T> {
        const items = [...values];
        if (options.nullable === false && items.some(item => item === null || item === undefined)) {
            throw new TypeError("This list does not accept null elements.");
        }
        if (options.unique === true
            && items.some((item, index) => items.findIndex(other => sameValue(other, item)) !== index)) {
            throw new Error("A value occurs more than once in this ordered set.");
        }
        requireCapacity(options.upperBound ?? -1, items.length);
        return new ReadOnlyList<T>(new FixedList(Object.freeze(items)), value => value as T);
    }

    /** @internal A live view of a model list whose elements are shown through `project`. */
    static __vmfView<T>(source: ListView<unknown>, project: (value: unknown) => T): ReadOnlyList<T> {
        return new ReadOnlyList(source, project);
    }

    get count(): number {
        return this.#source.count;
    }

    at(index: number): T {
        return this.#project(this.#source.at(index));
    }

    indexOf(value: T): number {
        return this.#source.indexOf(unwrapReadOnly(value));
    }

    contains(value: T): boolean {
        return this.#source.contains(unwrapReadOnly(value));
    }

    toArray(): T[] {
        return [...this];
    }

    *[Symbol.iterator](): IterableIterator<T> {
        for (const item of this.#source) {
            yield this.#project(item);
        }
    }
}

/** The read-only view of an unordered model collection. */
export class ReadOnlySet<T> implements CollectionView<T> {
    readonly #source: CollectionView<unknown>;
    readonly #project: (value: unknown) => T;

    private constructor(source: CollectionView<unknown>, project: (value: unknown) => T) {
        this.#source = source;
        this.#project = project;
    }

    /** A set of the given values that never changes; repeated values occur once, and null is rejected. */
    static of<T>(values: Iterable<T>, options: Pick<ValueOptions, "upperBound"> = {}): ReadOnlySet<T> {
        const items = new Set<T>();
        for (const value of values) {
            if (value === null || value === undefined) {
                throw new TypeError("A set does not accept null.");
            }
            items.add(value);
        }
        requireCapacity(options.upperBound ?? -1, items.size);
        return new ReadOnlySet<T>(new FixedSet(items), value => value as T);
    }

    /** @internal A live view of a model collection whose elements are shown through `project`. */
    static __vmfView<T>(source: CollectionView<unknown>, project: (value: unknown) => T): ReadOnlySet<T> {
        return new ReadOnlySet(source, project);
    }

    get count(): number {
        return this.#source.count;
    }

    contains(value: T): boolean {
        return this.#source.contains(unwrapReadOnly(value));
    }

    toArray(): T[] {
        return [...this];
    }

    *[Symbol.iterator](): IterableIterator<T> {
        for (const item of this.#source) {
            yield this.#project(item);
        }
    }
}

/**
 * A map that cannot be changed through this object: the map of an immutable value, or the read-only view of a map
 * property, which shows its current entries.
 */
export class ReadOnlyMap<K, V> implements MapView<K, V> {
    readonly #source: MapView<K, V>;

    private constructor(source: MapView<K, V>) {
        this.#source = source;
    }

    /**
     * A map of the given entries that never changes. Keys must not be null or repeat; values may be null. `options`
     * may bound the number of entries.
     */
    static of<K, V>(entries: Iterable<readonly [K, V]>, options: Pick<ValueOptions, "upperBound"> = {}): ReadOnlyMap<K, V> {
        const items = new Map<K, V>();
        for (const [key, value] of entries) {
            if (key === null || key === undefined) {
                throw new TypeError("A map key must not be null.");
            }
            if (items.has(key)) {
                throw new Error("A key occurs more than once in this map.");
            }
            items.set(key, (value === undefined ? null : value) as V);
        }
        requireCapacity(options.upperBound ?? -1, items.size);
        return new ReadOnlyMap(new FixedMap(items));
    }

    /** @internal A live view of a map property. */
    static __vmfView<K, V>(source: MapView<K, V>): ReadOnlyMap<K, V> {
        return new ReadOnlyMap(source);
    }

    get count(): number {
        return this.#source.count;
    }

    has(key: K): boolean {
        return this.#source.has(key);
    }

    get(key: K): V | undefined {
        return this.#source.get(key);
    }

    keys(): IterableIterator<K> {
        return this.#source.keys();
    }

    values(): IterableIterator<V> {
        return this.#source.values();
    }

    entries(): IterableIterator<[K, V]> {
        return this.#source.entries();
    }

    /** A copy of the entries as a JavaScript `Map`. */
    toMap(): Map<K, V> {
        return new Map(this.#source.entries());
    }

    [Symbol.iterator](): IterableIterator<[K, V]> {
        return this.#source.entries();
    }
}

class FixedMap<K, V> implements MapView<K, V> {
    constructor(readonly items: ReadonlyMap<K, V>) {
    }

    get count(): number {
        return this.items.size;
    }

    has(key: K): boolean {
        return this.items.has(key);
    }

    get(key: K): V | undefined {
        return this.items.get(key);
    }

    keys(): IterableIterator<K> {
        return this.items.keys();
    }

    values(): IterableIterator<V> {
        return this.items.values();
    }

    entries(): IterableIterator<[K, V]> {
        return this.items.entries();
    }

    [Symbol.iterator](): IterableIterator<[K, V]> {
        return this.items.entries();
    }
}

class FixedSet<T> implements CollectionView<T> {
    constructor(readonly items: ReadonlySet<T>) {
    }

    get count(): number {
        return this.items.size;
    }

    contains(value: T): boolean {
        return this.items.has(value);
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.items.values();
    }
}

class FixedList<T> implements ListView<T> {
    constructor(readonly items: readonly T[]) {
    }

    get count(): number {
        return this.items.length;
    }

    at(index: number): T {
        if (!Number.isInteger(index) || index < 0 || index >= this.items.length) {
            throw new RangeError(`Invalid collection index ${index}.`);
        }
        return this.items[index];
    }

    indexOf(value: T): number {
        return this.items.indexOf(value);
    }

    contains(value: T): boolean {
        return this.items.includes(value);
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.items.values();
    }
}

const views = new WeakMap<object, object>();
const models = new WeakMap<object, object>();
const listViews = new WeakMap<object, object>();
const prototypes = new WeakMap<ModelType, object>();
__vmfUseViews(value => models.get(value) ?? value);

/**
 * The base of read-only views. A view shows the current state of its model object; model objects and lists read
 * through it are read-only views too, and it offers no way to change the model.
 */
export abstract class ReadOnlyView {
    /** Views are created by the `asReadOnly()` method of model objects. */
    protected constructor() {
        throw new TypeError("Read-only views are created by asReadOnly().");
    }

    asReadOnly(): this {
        return this;
    }

    /** True if the other object (or the object the other view shows) is content-equal to the viewed object. */
    contentEquals(other: unknown): boolean {
        return content(this).contentEquals(unwrapReadOnly(other));
    }

    contentHashCode(): number {
        return content(this).contentHashCode();
    }

    /** Listeners for the viewed object; the changes show read-only views in place of model objects. */
    get vmfChanges(): ReadOnlyModelChanges {
        return new ReadOnlyModelChanges(modelOf(this));
    }

    get vmfType(): ModelType {
        return modelTypeOf(modelOf(this));
    }
}

/** Observation of a model object through its read-only view. */
export class ReadOnlyModelChanges {
    readonly #model: object;

    /** @internal */
    constructor(model: object) {
        this.#model = model;
    }

    /** Adds a listener and returns a function that removes it. */
    addListener(listener: ModelChangeListener, scope: ListenerScope = "recursive"): () => void {
        return changesOf(this.#model).addListener(change => listener(projectChange(change)), scope);
    }
}

/**
 * @internal The read-only view of a model object, used by the generated `asReadOnly()` methods. Immutable values
 * and views are returned as they are.
 */
export function readOnly<T>(model: object): T {
    if (models.has(model)) {
        return model as T;
    }
    const type = modelTypeOf(model);
    if (type.immutable) {
        return model as T;
    }
    let view = views.get(model);
    if (view === undefined) {
        view = Object.freeze(Object.create(viewPrototype(type)) as object);
        models.set(view, model);
        views.set(model, view);
    }
    return view as T;
}

/** The model object a read-only view shows; any other value is returned as it is. */
export function unwrapReadOnly<T>(value: T): T {
    return (typeof value === "object" && value !== null ? models.get(value) ?? value : value) as T;
}

/** @internal The mutator of an immutable property in a snapshot registry: immutable objects cannot change. */
export function rejectEdit(): never {
    throw new TypeError("An immutable model object cannot be changed.");
}

/** True for the read-only view of a model object. */
export function isReadOnlyView(value: unknown): boolean {
    return typeof value === "object" && value !== null && models.has(value);
}

function viewPrototype(type: ModelType): object {
    let prototype = prototypes.get(type);
    if (prototype === undefined) {
        prototype = Object.create(ReadOnlyView.prototype) as object;
        for (const property of type.properties) {
            const name = property.name;
            Object.defineProperty(prototype, name, {
                get(this: object): unknown {
                    return project((modelOf(this) as Record<string, unknown>)[name]);
                },
                enumerable: true,
            });
        }
        // Lookups of keyed containments accept views as keys and return views.
        for (const lookup of __vmfLookupsOf(type)) {
            Object.defineProperty(prototype, lookup, {
                value(this: object, key: unknown): unknown {
                    const model = modelOf(this) as Record<string, (key: unknown) => unknown>;
                    return project(model[lookup](unwrapReadOnly(key)));
                },
            });
        }
        prototypes.set(type, Object.freeze(prototype));
    }
    return prototype;
}

function modelOf(view: object): object {
    const model = models.get(view);
    if (model === undefined) {
        throw new TypeError("Expected a read-only view.");
    }
    return model;
}

function content(view: object): { contentEquals(other: unknown): boolean; contentHashCode(): number } {
    return modelOf(view) as { contentEquals(other: unknown): boolean; contentHashCode(): number };
}

/** A value read through a view: model objects and lists become read-only views; other values stay as they are. */
function project(value: unknown): unknown {
    if (typeof value !== "object" || value === null || models.has(value)) {
        return value;
    }
    if (value instanceof ScalarList || value instanceof ContainmentList || value instanceof ReferenceList
        || value instanceof ReferenceSet || value instanceof ScalarSet || value instanceof ContainmentSet
        || value instanceof ScalarMap) {
        let view = listViews.get(value);
        if (view === undefined) {
            if (value instanceof ScalarMap) {
                view = ReadOnlyMap.__vmfView(value);
            } else if (value instanceof ReferenceSet || value instanceof ScalarSet || value instanceof ContainmentSet) {
                view = ReadOnlySet.__vmfView(value as CollectionView<unknown>, project);
            } else {
                view = ReadOnlyList.__vmfView(value as ListView<unknown>, project);
            }
            listViews.set(value, view);
        }
        return view;
    }
    return findModelType(value) === undefined ? value : readOnly(value);
}

function projectChange(change: ModelChange): ModelChange {
    const projected: {
        source: object; property: string; kind: ModelChange["kind"]; oldValue?: unknown; newValue?: unknown;
        values?: readonly unknown[]; replaced?: readonly unknown[]; indices?: readonly number[];
        keys?: readonly unknown[];
    } = { source: project(change.source) as object, property: change.property, kind: change.kind };
    if ("oldValue" in change) {
        projected.oldValue = project(change.oldValue);
    }
    if ("newValue" in change) {
        projected.newValue = project(change.newValue);
    }
    if (change.values !== undefined) {
        projected.values = Object.freeze(change.values.map(project));
    }
    if (change.replaced !== undefined) {
        projected.replaced = Object.freeze(change.replaced.map(project));
    }
    if (change.indices !== undefined) {
        projected.indices = change.indices;
    }
    if (change.keys !== undefined) {
        projected.keys = Object.freeze(change.keys.map(project));
    }
    return Object.freeze(projected);
}
