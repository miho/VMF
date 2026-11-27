interface Attachment {
    readonly owner: object;
    readonly collection: object;
    indexOf(child: object): number;
    removeAt(index: number): void;
    detach(link: ContainmentLink, child: object): boolean;
}

const attachments = new WeakMap<ContainmentLink, Attachment>();

/** Tracks a child's single containing relationship across owner and child model types. */
export class ContainmentLink {
    get owner(): object | undefined {
        return attachments.get(this)?.owner;
    }

    get collection(): object | undefined {
        return attachments.get(this)?.collection;
    }

    detach(child: object): boolean {
        return attachments.get(this)?.detach(this, child) ?? false;
    }
}

/** An ordered identity collection that maintains containment when a child moves. */
export class ContainmentList<TParent extends object, TChild extends object> implements Iterable<TChild> {
    readonly #items: TChild[] = [];
    readonly #attachment: Attachment;

    constructor(readonly owner: TParent, private readonly getLink: (child: TChild) => ContainmentLink) {
        this.#attachment = {
            owner,
            collection: this,
            indexOf: child => this.#items.findIndex(item => item === child),
            removeAt: index => { this.#items.splice(index, 1); },
            detach: (link, child) => {
                const index = this.#items.findIndex(item => item === child);
                return index >= 0 && this.getLink(this.#items[index]) === link && this.remove(this.#items[index]);
            },
        };
    }

    get count(): number {
        return this.#items.length;
    }

    at(index: number): TChild {
        requireIndex(index, this.count - 1);
        return this.#items[index];
    }

    contains(child: TChild): boolean {
        return this.#items.includes(child);
    }

    add(child: TChild): boolean {
        requireObject(child);
        if (this.contains(child)) {
            return false;
        }

        this.attach(this.count, child);
        return true;
    }

    insert(index: number, child: TChild): void {
        requireIndex(index, this.count);
        requireObject(child);
        if (this.contains(child)) {
            throw new Error("The child is already in this containment.");
        }

        this.attach(index, child);
    }

    remove(child: TChild): boolean {
        const index = this.#items.indexOf(child);
        if (index < 0) {
            return false;
        }

        const link = this.getLink(child);
        if (attachments.get(link) !== this.#attachment) {
            throw new Error("The child and its containment are inconsistent.");
        }

        this.#items.splice(index, 1);
        attachments.delete(link);
        return true;
    }

    move(oldIndex: number, newIndex: number): void {
        const child = this.at(oldIndex);
        requireIndex(newIndex, this.count - 1);
        if (oldIndex !== newIndex) {
            this.#items.splice(oldIndex, 1);
            this.#items.splice(newIndex, 0, child);
        }
    }

    [Symbol.iterator](): IterableIterator<TChild> {
        return this.#items.values();
    }

    private attach(index: number, child: TChild): void {
        const link = this.getLink(child);
        if (!(link instanceof ContainmentLink)) {
            throw new TypeError("The child does not have a VMF containment link.");
        }

        const previous = attachments.get(link);
        const previousIndex = previous?.indexOf(child) ?? -1;
        if (previous !== undefined && previousIndex < 0) {
            throw new Error("The child and its containment are inconsistent.");
        }

        this.#items.splice(index, 0, child);
        previous?.removeAt(previousIndex);
        attachments.set(link, this.#attachment);
    }
}

/** An unordered collection of distinct model references, compared by identity. */
export class ReferenceSet<T extends object> implements Iterable<T> {
    readonly #items = new Set<T>();

    get count(): number {
        return this.#items.size;
    }

    add(item: T): boolean {
        requireObject(item);
        if (this.#items.has(item)) {
            return false;
        }

        this.#items.add(item);
        return true;
    }

    remove(item: T): boolean {
        return this.#items.delete(item);
    }

    contains(item: T): boolean {
        return this.#items.has(item);
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

function requireObject(value: unknown): asserts value is object {
    if (typeof value !== "object" || value === null) {
        throw new TypeError("A model relationship requires a non-null object.");
    }
}
