import { __vmfEffect as effect, edit } from "./changes.js";

/** An ordered list of scalar values; the same value may occur several times. */
export class ScalarList<T> implements Iterable<T> {
    readonly #items: T[] = [];
    readonly #normalize: (value: T) => T;

    /** `normalize` checks a value before it enters the list and returns the form the list keeps. */
    constructor(readonly owner: object, readonly property: string, normalize: (value: T) => T = value => value) {
        this.#normalize = normalize;
    }

    get count(): number {
        return this.#items.length;
    }

    at(index: number): T {
        requireIndex(index, this.count - 1);
        return this.#items[index];
    }

    indexOf(value: T): number {
        return this.#items.indexOf(value);
    }

    contains(value: T): boolean {
        return this.#items.includes(value);
    }

    add(value: T): boolean {
        value = this.#normalize(value);
        edit(this.owner, () => this.#insert(this.count, value));
        return true;
    }

    insert(index: number, value: T): void {
        requireIndex(index, this.count);
        value = this.#normalize(value);
        edit(this.owner, () => this.#insert(index, value));
    }

    set(index: number, value: T): T {
        requireIndex(index, this.count - 1);
        value = this.#normalize(value);
        const previous = this.#items[index];
        if (Object.is(previous, value)) {
            return previous;
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

    #insert(index: number, value: T): void {
        this.#items.splice(index, 0, value);
        effect({ source: this.owner, property: this.property, kind: "add", values: [value], indices: [index] },
            () => { this.#items.splice(index, 1); }, () => { this.#items.splice(index, 0, value); });
    }
}

function requireIndex(index: number, max: number): void {
    if (!Number.isInteger(index) || index < 0 || index > max) {
        throw new RangeError(`Invalid collection index ${index}.`);
    }
}
