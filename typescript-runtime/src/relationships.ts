type ModelClass<T> = abstract new (...args: never[]) => T;

/** The place a contained child occupies: its owner, the owning property and how to leave it. */
interface Attachment {
    readonly owner: object;
    readonly property: string;
    readonly collection: object;
    indexOf(child: object): number;
    /** Removes the child while it moves elsewhere; the caller attaches it again. */
    removeAt(index: number): void;
    /** Removes the child because its container was set to null. */
    detach(child: object): boolean;
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

    /** The model name of the containment property that holds the child. */
    get property(): string | undefined {
        return attachments.get(this)?.property;
    }

    detach(child: object): boolean {
        return attachments.get(this)?.detach(child) ?? false;
    }
}

/** Returns the owner if it is an instance of the owner type and holds the child in one of the given properties. */
export function containerOf<T extends object>(link: ContainmentLink, ownerType: ModelClass<T>,
                                              properties: readonly string[]): T | null {
    const attachment = attachments.get(link);
    return attachment !== undefined && attachment.owner instanceof ownerType
        && properties.includes(attachment.property) ? attachment.owner as T : null;
}

/** An ordered identity collection that maintains containment when a child moves. */
export class ContainmentList<TParent extends object, TChild extends object> implements Iterable<TChild> {
    readonly #items: TChild[] = [];
    readonly #attachment: Attachment;

    constructor(readonly owner: TParent, property: string, private readonly getLink: (child: TChild) => ContainmentLink) {
        this.#attachment = {
            owner,
            property,
            collection: this,
            indexOf: child => this.#items.indexOf(child as TChild),
            removeAt: index => { this.#items.splice(index, 1); },
            detach: child => this.remove(child as TChild),
        };
    }

    get count(): number {
        return this.#items.length;
    }

    at(index: number): TChild {
        requireIndex(index, this.count - 1);
        return this.#items[index];
    }

    indexOf(child: TChild): number {
        return this.#items.indexOf(child);
    }

    contains(child: TChild): boolean {
        return this.#items.includes(child);
    }

    /** Appends the child, moving it from its previous container. Returns false if it is already here. */
    add(child: TChild): boolean {
        requireObject(child);
        if (this.contains(child)) {
            return false;
        }

        this.#attach(this.count, child);
        return true;
    }

    insert(index: number, child: TChild): void {
        requireIndex(index, this.count);
        requireObject(child);
        if (this.contains(child)) {
            throw new Error("The child is already in this containment.");
        }

        this.#attach(index, child);
    }

    /** Replaces the child at the index and releases the previous one. */
    set(index: number, child: TChild): TChild {
        requireIndex(index, this.count - 1);
        requireObject(child);
        const previous = this.#items[index];
        if (previous === child) {
            return previous;
        }
        if (this.contains(child)) {
            throw new Error("The child is already in this containment.");
        }

        requireNoCycle(this.owner, child);
        const link = this.#link(child);
        leavePrevious(link, child);
        attachments.delete(this.getLink(previous));
        this.#items[index] = child;
        attachments.set(link, this.#attachment);
        return previous;
    }

    remove(child: TChild): boolean {
        const index = this.indexOf(child);
        if (index < 0) {
            return false;
        }

        this.removeAt(index);
        return true;
    }

    removeAt(index: number): TChild {
        requireIndex(index, this.count - 1);
        const child = this.#items[index];
        const link = this.getLink(child);
        if (attachments.get(link) !== this.#attachment) {
            throw new Error("The child and its containment are inconsistent.");
        }

        this.#items.splice(index, 1);
        attachments.delete(link);
        return child;
    }

    clear(): void {
        for (const child of this.#items.splice(0)) {
            attachments.delete(this.getLink(child));
        }
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

    #link(child: TChild): ContainmentLink {
        const link = this.getLink(child);
        if (!(link instanceof ContainmentLink)) {
            throw new TypeError("The child does not have a VMF containment link.");
        }
        return link;
    }

    #attach(index: number, child: TChild): void {
        requireNoCycle(this.owner, child);
        const link = this.#link(child);
        leavePrevious(link, child);
        this.#items.splice(index, 0, child);
        attachments.set(link, this.#attachment);
    }
}

/** A single-valued containment property. */
export class ContainmentSlot<TParent extends object, TChild extends object> {
    #value: TChild | null = null;
    readonly #attachment: Attachment;

    constructor(readonly owner: TParent, property: string, private readonly getLink: (child: TChild) => ContainmentLink) {
        this.#attachment = {
            owner,
            property,
            collection: this,
            indexOf: child => child === this.#value ? 0 : -1,
            removeAt: () => { this.#value = null; },
            detach: child => {
                if (child !== this.#value) {
                    return false;
                }
                this.set(null);
                return true;
            },
        };
    }

    get value(): TChild | null {
        return this.#value;
    }

    /** Stores the child, moving it from its previous container and releasing the current child. */
    set(child: TChild | null): void {
        if (child === this.#value) {
            return;
        }
        let link: ContainmentLink | undefined;
        if (child !== null) {
            requireObject(child);
            requireNoCycle(this.owner, child);
            link = this.getLink(child);
            if (!(link instanceof ContainmentLink)) {
                throw new TypeError("The child does not have a VMF containment link.");
            }
            leavePrevious(link, child);
        }
        if (this.#value !== null) {
            attachments.delete(this.getLink(this.#value));
        }
        this.#value = child;
        if (link !== undefined) {
            attachments.set(link, this.#attachment);
        }
    }
}

function leavePrevious(link: ContainmentLink, child: object): void {
    const previous = attachments.get(link);
    if (previous === undefined) {
        return;
    }
    const index = previous.indexOf(child);
    if (index < 0) {
        throw new Error("The child and its containment are inconsistent.");
    }
    previous.removeAt(index);
    attachments.delete(link);
}

function linkOf(model: object): ContainmentLink | undefined {
    const link = (model as { __vmfContainerLink?: unknown }).__vmfContainerLink;
    return link instanceof ContainmentLink ? link : undefined;
}

/** Rejects an edit that would make the child contain its new owner. */
function requireNoCycle(owner: object, child: object): void {
    for (let current: object | undefined = owner; current !== undefined;) {
        if (current === child) {
            throw new Error("The containment edit would create a cycle.");
        }
        const link = linkOf(current);
        current = link === undefined ? undefined : attachments.get(link)?.owner;
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

    clear(): void {
        this.#items.clear();
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }
}

/** Storage of a single-valued reference that has an opposite. Change it through the owning property only. */
export class ReferenceSlot<T extends object> {
    value: T | null = null;
}

type ReferenceEnd = { readonly slot: ReferenceSlot<object> } | { readonly list: ReferenceList<object> };

function endOf(model: object, property: string): ReferenceEnd {
    const members = model as Record<string, unknown>;
    const slot = members["__vmfRef_" + property];
    if (slot instanceof ReferenceSlot) {
        return { slot };
    }
    const list = members[property];
    if (list instanceof ReferenceList) {
        return { list };
    }
    throw new TypeError(`'${property}' is not a reference with an opposite.`);
}

function unlink(target: object, opposite: string, owner: object): void {
    const end = endOf(target, opposite);
    if ("slot" in end) {
        if (end.slot.value === owner) {
            end.slot.value = null;
        }
    } else {
        end.list.__vmfRemoveRaw(owner);
    }
}

function link(target: object, opposite: string, owner: object): void {
    const end = endOf(target, opposite);
    if ("slot" in end) {
        end.slot.value = owner;
    } else if (!end.list.contains(owner)) {
        end.list.__vmfAppendRaw(owner);
    }
}

/**
 * Sets a single-valued reference and keeps both ends consistent: the old target loses its opposite, and in a
 * one-to-one pair the new target's previous partner loses its reference.
 */
export function setReference(owner: object, value: object | null, property: string, opposite: string): void {
    const own = endOf(owner, property);
    if (!("slot" in own)) {
        throw new TypeError(`'${property}' is not a single-valued reference.`);
    }
    const previous = own.slot.value;
    if (previous === value) {
        return;
    }
    if (value !== null) {
        requireObject(value);
        const target = endOf(value, opposite);
        if ("slot" in target && target.slot.value !== null && target.slot.value !== owner) {
            const partner = endOf(target.slot.value, property);
            if ("slot" in partner) {
                partner.slot.value = null;
            }
        }
    }
    if (previous !== null) {
        unlink(previous, opposite, owner);
    }
    own.slot.value = value;
    if (value !== null) {
        link(value, opposite, owner);
    }
}

/**
 * An ordered list of model references. Without an opposite it may hold the same object several times; with an
 * opposite each object occurs once and the opposite property of every member points back to the owner.
 */
export class ReferenceList<T extends object> implements Iterable<T> {
    readonly #items: T[] = [];

    constructor(readonly owner: object, private readonly property?: string, private readonly opposite?: string) {
    }

    get count(): number {
        return this.#items.length;
    }

    at(index: number): T {
        requireIndex(index, this.count - 1);
        return this.#items[index];
    }

    indexOf(item: T): number {
        return this.#items.indexOf(item);
    }

    contains(item: T): boolean {
        return this.#items.includes(item);
    }

    /** Appends the item. Returns false if a list with an opposite already holds it. */
    add(item: T): boolean {
        requireObject(item);
        if (this.opposite !== undefined && !this.#linkFrom(item)) {
            return false;
        }
        this.#items.push(item);
        return true;
    }

    insert(index: number, item: T): void {
        requireIndex(index, this.count);
        requireObject(item);
        if (this.opposite !== undefined) {
            if (this.contains(item)) {
                throw new Error("The reference is already in this list.");
            }
            this.#linkFrom(item);
        }
        this.#items.splice(index, 0, item);
    }

    set(index: number, item: T): T {
        requireIndex(index, this.count - 1);
        requireObject(item);
        const previous = this.#items[index];
        if (previous === item) {
            return previous;
        }
        if (this.opposite === undefined) {
            this.#items[index] = item;
            return previous;
        }
        if (this.contains(item)) {
            throw new Error("The reference is already in this list.");
        }
        this.removeAt(index);
        this.insert(index, item);
        return previous;
    }

    remove(item: T): boolean {
        const index = this.indexOf(item);
        if (index < 0) {
            return false;
        }
        this.removeAt(index);
        return true;
    }

    removeAt(index: number): T {
        requireIndex(index, this.count - 1);
        const [item] = this.#items.splice(index, 1);
        if (this.opposite !== undefined) {
            unlink(item, this.opposite, this.owner);
        }
        return item;
    }

    clear(): void {
        const items = this.#items.splice(0);
        if (this.opposite !== undefined) {
            for (const item of items) {
                unlink(item, this.opposite, this.owner);
            }
        }
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }

    /** @internal Appends without touching the opposite. */
    __vmfAppendRaw(item: object): void {
        this.#items.push(item as T);
    }

    /** @internal Removes the first occurrence without touching the opposite. */
    __vmfRemoveRaw(item: object): void {
        const index = this.#items.indexOf(item as T);
        if (index >= 0) {
            this.#items.splice(index, 1);
        }
    }

    /** Points the item's opposite to this owner. Returns false if both ends are linked already. */
    #linkFrom(item: T): boolean {
        const end = endOf(item, this.opposite!);
        if ("slot" in end) {
            const previous = end.slot.value;
            if (previous === this.owner) {
                return false;
            }
            if (previous !== null) {
                const list = endOf(previous, this.property!);
                if ("list" in list) {
                    list.list.__vmfRemoveRaw(item);
                }
            }
            end.slot.value = this.owner;
            return true;
        }
        if (this.contains(item)) {
            return false;
        }
        if (!end.list.contains(this.owner)) {
            end.list.__vmfAppendRaw(this.owner);
        }
        return true;
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
