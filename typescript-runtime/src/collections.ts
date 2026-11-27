import { __vmfEffect as effect, edit } from "./changes.js";

/**
 * How a scalar list checks its elements. A unique list is an ordered set (`@Multiplicity(kind = ORDERED_SET)`);
 * a bounded list rejects edits beyond `@Multiplicity(upper)`. An ordinary list (no options) holds anything.
 */
export interface ScalarListOptions {
    /** Each value occurs at most once. */
    readonly unique?: boolean;
    /** The most elements the list may hold; -1, the default, is unbounded. */
    readonly upperBound?: number;
    /** False rejects null elements. */
    readonly nullable?: boolean;
}

/**
 * An ordered list of scalar values. An ordinary list may hold the same value several times; a unique list holds
 * each value once, and a bounded list holds at most `upperBound` values.
 */
export class ScalarList<T> implements Iterable<T> {
    readonly #items: T[] = [];
    readonly #unique: boolean;
    readonly #upperBound: number;
    readonly #nullable: boolean;

    constructor(readonly owner: object, readonly property: string, options: ScalarListOptions = {}) {
        this.#unique = options.unique === true;
        this.#upperBound = checkedUpperBound(options.upperBound);
        this.#nullable = options.nullable !== false;
    }

    get count(): number {
        return this.#items.length;
    }

    at(index: number): T {
        requireIndex(index, this.count - 1);
        return this.#items[index];
    }

    indexOf(value: T): number {
        return this.#items.findIndex(item => sameValue(item, value));
    }

    contains(value: T): boolean {
        return this.indexOf(value) >= 0;
    }

    /** Appends the value. Returns false if a unique list already holds it. */
    add(value: T): boolean {
        this.#requireValue(value);
        if (this.#unique && this.contains(value)) {
            return false;
        }
        requireCapacity(this.#upperBound, this.count + 1);
        edit(this.owner, () => this.#insert(this.count, value));
        return true;
    }

    /**
     * Appends the values as one edit. A unique list skips the values it holds or that occur earlier in `values`.
     * Rejects the whole edit if the list would exceed its upper bound. Returns true if the list changed.
     */
    addAll(values: Iterable<T>): boolean {
        const added: T[] = [];
        for (const value of values) {
            this.#requireValue(value);
            if (!this.#unique || (!this.contains(value) && !added.some(item => sameValue(item, value)))) {
                added.push(value);
            }
        }
        if (added.length === 0) {
            return false;
        }
        requireCapacity(this.#upperBound, this.count + added.length);
        edit(this.owner, () => {
            const start = this.count;
            this.#items.push(...added);
            effect({ source: this.owner, property: this.property, kind: "add", values: [...added],
                indices: added.map((_, offset) => start + offset) },
            () => { this.#items.splice(start, added.length); }, () => { this.#items.splice(start, 0, ...added); });
        });
        return true;
    }

    /** Inserts the value at the index. A unique list rejects a value it already holds. */
    insert(index: number, value: T): void {
        requireIndex(index, this.count);
        this.#requireValue(value);
        if (this.#unique && this.contains(value)) {
            throw new Error("The value is already in this ordered set.");
        }
        requireCapacity(this.#upperBound, this.count + 1);
        edit(this.owner, () => this.#insert(index, value));
    }

    /** Replaces the element at the index. A unique list rejects a value it holds at another index. */
    set(index: number, value: T): T {
        requireIndex(index, this.count - 1);
        this.#requireValue(value);
        const previous = this.#items[index];
        if (Object.is(previous, value)) {
            return previous;
        }
        if (this.#unique) {
            const existing = this.indexOf(value);
            if (existing >= 0 && existing !== index) {
                throw new Error("The value is already in this ordered set at another index.");
            }
        }
        edit(this.owner, () => {
            this.#items[index] = value;
            effect({ source: this.owner, property: this.property, kind: "replace", values: [value], replaced: [previous],
                indices: [index] }, () => { this.#items[index] = previous; }, () => { this.#items[index] = value; });
        });
        return previous;
    }

    /** Removes the first occurrence of the value. */
    remove(value: T): boolean {
        const index = this.indexOf(value);
        if (index < 0) {
            return false;
        }
        this.removeAt(index);
        return true;
    }

    removeAt(index: number): T {
        requireIndex(index, this.count - 1);
        const value = this.#items[index];
        edit(this.owner, () => {
            this.#items.splice(index, 1);
            effect({ source: this.owner, property: this.property, kind: "remove", values: [value], indices: [index] },
                () => { this.#items.splice(index, 0, value); }, () => { this.#items.splice(index, 1); });
        });
        return value;
    }

    clear(): void {
        if (this.count === 0) {
            return;
        }
        edit(this.owner, () => {
            const values = this.#items.splice(0);
            effect({ source: this.owner, property: this.property, kind: "remove", values: [...values],
                indices: values.map((_, index) => index) },
            () => { this.#items.push(...values); }, () => { this.#items.splice(0); });
        });
    }

    toArray(): T[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }

    #requireValue(value: T): void {
        if (!this.#nullable && (value === null || value === undefined)) {
            throw new TypeError("This list does not accept null elements.");
        }
    }

    #insert(index: number, value: T): void {
        this.#items.splice(index, 0, value);
        effect({ source: this.owner, property: this.property, kind: "add", values: [value], indices: [index] },
            () => { this.#items.splice(index, 1); }, () => { this.#items.splice(index, 0, value); });
    }
}

/**
 * An unordered set of scalar values (`Set<T>`): each value occurs once, `null` is rejected, and a bounded set holds
 * at most `upperBound` values. Values compare like JavaScript's `Set` (SameValueZero).
 */
export class ScalarSet<T> implements Iterable<T> {
    readonly #items = new Set<T>();
    readonly #upperBound: number;

    constructor(readonly owner: object, readonly property: string, upperBound = -1) {
        this.#upperBound = checkedUpperBound(upperBound);
    }

    get count(): number {
        return this.#items.size;
    }

    contains(value: T): boolean {
        return this.#items.has(value);
    }

    /** Adds the value. Returns false if the set already holds it. */
    add(value: T): boolean {
        return this.addAll([value]);
    }

    /** Adds the values as one edit; rejects the whole edit if the set would exceed its upper bound. */
    addAll(values: Iterable<T>): boolean {
        const added = new Set<T>();
        for (const value of values) {
            requireMember(value);
            if (!this.#items.has(value)) {
                added.add(value);
            }
        }
        if (added.size === 0) {
            return false;
        }
        requireCapacity(this.#upperBound, this.count + added.size);
        edit(this.owner, () => {
            added.forEach(value => this.#items.add(value));
            effect({ source: this.owner, property: this.property, kind: "add", values: [...added] },
                () => added.forEach(value => this.#items.delete(value)), () => added.forEach(value => this.#items.add(value)));
        });
        return true;
    }

    remove(value: T): boolean {
        if (!this.#items.has(value)) {
            return false;
        }
        edit(this.owner, () => {
            this.#items.delete(value);
            effect({ source: this.owner, property: this.property, kind: "remove", values: [value] },
                () => { this.#items.add(value); }, () => { this.#items.delete(value); });
        });
        return true;
    }

    clear(): void {
        if (this.#items.size === 0) {
            return;
        }
        edit(this.owner, () => {
            const values = [...this.#items];
            this.#items.clear();
            effect({ source: this.owner, property: this.property, kind: "remove", values },
                () => values.forEach(value => this.#items.add(value)), () => { this.#items.clear(); });
        });
    }

    toArray(): T[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }
}

/**
 * A map of scalar keys to scalar values (`VMap<K, V>`). Keys are never `null`; values may be `null`. Entries keep
 * the order in which their keys were first put; `put` replaces the value of a present key in place. A bounded map
 * holds at most `upperBound` entries. Keys compare like JavaScript's `Map` (SameValueZero).
 */
export class ScalarMap<K, V> implements Iterable<[K, V]> {
    readonly #entries = new Map<K, V>();
    readonly #upperBound: number;

    constructor(readonly owner: object, readonly property: string, upperBound = -1) {
        this.#upperBound = checkedUpperBound(upperBound);
    }

    get count(): number {
        return this.#entries.size;
    }

    has(key: K): boolean {
        return this.#entries.has(key);
    }

    /** The value of the key, or undefined if the map has no such key (a present key may hold `null`). */
    get(key: K): V | undefined {
        return this.#entries.get(key);
    }

    /** Puts a value for a new key at the end, or replaces the value of a present key in place. Returns the previous value. */
    put(key: K, value: V): V | undefined {
        requireKey(key);
        const previous = this.#entries.get(key);
        if (!this.#entries.has(key)) {
            requireCapacity(this.#upperBound, this.count + 1);
        }
        edit(this.owner, () => this.#put(key, value));
        return previous;
    }

    /** Adds an entry for a new key; rejects a key the map already has. */
    add(key: K, value: V): void {
        requireKey(key);
        if (this.#entries.has(key)) {
            throw new Error("The map already has this key.");
        }
        this.put(key, value);
    }

    /** Puts the entries in their order as one edit; rejects the whole edit if the map would exceed its upper bound. */
    putAll(entries: Iterable<readonly [K, V]>): void {
        const pending = new Map<K, V>();
        for (const [key, value] of entries) {
            requireKey(key);
            pending.set(key, value);
        }
        let size = this.count;
        pending.forEach((_, key) => {
            if (!this.#entries.has(key)) {
                size++;
            }
        });
        requireCapacity(this.#upperBound, size);
        if (pending.size > 0) {
            edit(this.owner, () => pending.forEach((value, key) => this.#put(key, value)));
        }
    }

    /** Removes the entry of the key. Returns false if the map has no such key. */
    remove(key: K): boolean {
        if (!this.#entries.has(key)) {
            return false;
        }
        edit(this.owner, () => {
            const value = this.#entries.get(key) as V;
            const index = [...this.#entries.keys()].findIndex(candidate => sameValue(candidate, key));
            this.#entries.delete(key);
            effect({ source: this.owner, property: this.property, kind: "remove", keys: [key], values: [value] },
                () => this.#restore(index, key, value), () => { this.#entries.delete(key); });
        });
        return true;
    }

    clear(): void {
        if (this.#entries.size === 0) {
            return;
        }
        edit(this.owner, () => {
            const entries = [...this.#entries];
            this.#entries.clear();
            effect({ source: this.owner, property: this.property, kind: "remove", keys: entries.map(([key]) => key),
                values: entries.map(([, value]) => value) },
            () => entries.forEach(([key, value]) => this.#entries.set(key, value)), () => { this.#entries.clear(); });
        });
    }

    keys(): IterableIterator<K> {
        return this.#entries.keys();
    }

    values(): IterableIterator<V> {
        return this.#entries.values();
    }

    entries(): IterableIterator<[K, V]> {
        return this.#entries.entries();
    }

    /** A copy of the entries as a JavaScript `Map`. */
    toMap(): Map<K, V> {
        return new Map(this.#entries);
    }

    [Symbol.iterator](): IterableIterator<[K, V]> {
        return this.#entries.entries();
    }

    #put(key: K, value: V): void {
        const stored = (value === undefined ? null : value) as V;
        if (this.#entries.has(key)) {
            const previous = this.#entries.get(key) as V;
            this.#entries.set(key, stored);
            effect({ source: this.owner, property: this.property, kind: "replace", keys: [key], values: [stored],
                replaced: [previous] }, () => { this.#entries.set(key, previous); }, () => { this.#entries.set(key, stored); });
        } else {
            this.#entries.set(key, stored);
            effect({ source: this.owner, property: this.property, kind: "add", keys: [key], values: [stored] },
                () => { this.#entries.delete(key); }, () => { this.#entries.set(key, stored); });
        }
    }

    /** Puts a removed entry back at its former position. */
    #restore(index: number, key: K, value: V): void {
        const entries = [...this.#entries];
        entries.splice(index, 0, [key, value]);
        this.#entries.clear();
        entries.forEach(([entryKey, entryValue]) => this.#entries.set(entryKey, entryValue));
    }
}

/** SameValueZero, the equality of JavaScript's `Set`, `Map` and `includes`. */
export function sameValue(left: unknown, right: unknown): boolean {
    return left === right || (left !== left && right !== right);
}

/** @internal Rejects an edit that would make a bounded collection hold more than `upperBound` elements. */
export function requireCapacity(upperBound: number, size: number): void {
    if (upperBound >= 0 && size > upperBound) {
        throw new RangeError(`The collection cannot hold more than ${upperBound} elements.`);
    }
}

function checkedUpperBound(upperBound: number | undefined): number {
    const bound = upperBound ?? -1;
    if (!Number.isInteger(bound) || bound < -1) {
        throw new RangeError(`Invalid upper bound ${bound}.`);
    }
    return bound;
}

function requireMember(value: unknown): void {
    if (value === null || value === undefined) {
        throw new TypeError("A set does not accept null.");
    }
}

function requireKey(key: unknown): void {
    if (key === null || key === undefined) {
        throw new TypeError("A map key must not be null.");
    }
}

function requireIndex(index: number, max: number): void {
    if (!Number.isInteger(index) || index < 0 || index > max) {
        throw new RangeError(`Invalid collection index ${index}.`);
    }
}
