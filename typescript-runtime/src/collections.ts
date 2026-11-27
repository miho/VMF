/** An ordered list of scalar values; the same value may occur several times. */
export class ScalarList<T> implements Iterable<T> {
    readonly #items: T[] = [];

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
        this.#items.push(value);
        return true;
    }

    insert(index: number, value: T): void {
        requireIndex(index, this.count);
        this.#items.splice(index, 0, value);
    }

    set(index: number, value: T): T {
        requireIndex(index, this.count - 1);
        const previous = this.#items[index];
        this.#items[index] = value;
        return previous;
    }

    /** Removes the first occurrence of the value. */
    remove(value: T): boolean {
        const index = this.indexOf(value);
        if (index < 0) {
            return false;
        }
        this.#items.splice(index, 1);
        return true;
    }

    removeAt(index: number): T {
        requireIndex(index, this.count - 1);
        return this.#items.splice(index, 1)[0];
    }

    clear(): void {
        this.#items.length = 0;
    }

    toArray(): T[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }
}

function requireIndex(index: number, max: number): void {
    if (!Number.isInteger(index) || index < 0 || index > max) {
        throw new RangeError(`Invalid collection index ${index}.`);
    }
}
