import { sameValue } from "./collections.js";
import { __vmfEffect as effect, __vmfUseContainers, edit } from "./changes.js";

type ModelClass<T> = abstract new (...args: never[]) => T;

/** The place a contained child occupies: its owner, the owning property and how to leave it. */
interface Attachment {
    readonly owner: object;
    readonly property: string;
    /** The child's container property that names this containment, if any. */
    readonly opposite: string | undefined;
    collection: object;
    /** Removes the child as part of the active edit, because it moves elsewhere or its container is cleared. */
    leave(child: object): void;
    /** Rejects a change of the child's key property that a keyed containment does not allow. */
    requireKey?(child: object, property: string, value: unknown): void;
}

const attachments = new WeakMap<ContainmentLink, Attachment>();
const nothing = (): void => {
};

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

    /** Removes the child from its container. */
    detach(child: object): boolean {
        const attachment = attachments.get(this);
        if (attachment === undefined) {
            return false;
        }
        edit(child, () => attachment.leave(child));
        return true;
    }
}

/** Returns the owner if it is an instance of the owner type and holds the child in one of the given properties. */
export function containerOf<T extends object>(link: ContainmentLink, ownerType: ModelClass<T>,
                                              properties: readonly string[]): T | null {
    const attachment = attachments.get(link);
    return attachment !== undefined && attachment.owner instanceof ownerType
        && properties.includes(attachment.property) ? attachment.owner as T : null;
}

/**
 * @internal The containment link of a child whose declared type does not show it, such as a getter-only trait that
 * only mutable classes implement.
 */
export function containmentLinkOf(child: object): ContainmentLink {
    const link = linkOf(child);
    if (link === undefined) {
        throw new TypeError("Only mutable model objects can be contained.");
    }
    return link;
}

function linkOf(model: object): ContainmentLink | undefined {
    const link = (model as { __vmfContainerLink?: unknown }).__vmfContainerLink;
    return link instanceof ContainmentLink ? link : undefined;
}

function ownerOf(model: object): object | undefined {
    const link = linkOf(model);
    return link === undefined ? undefined : attachments.get(link)?.owner;
}

__vmfUseContainers(ownerOf);

/** Rejects an edit that would make the child contain its new owner. */
function requireNoCycle(owner: object, child: object): void {
    for (let current: object | undefined = owner; current !== undefined; current = ownerOf(current)) {
        if (current === child) {
            throw new Error("The containment edit would create a cycle.");
        }
    }
}

function leavePrevious(link: ContainmentLink, child: object): void {
    attachments.get(link)?.leave(child);
}

/** Reports a change of the child's container property; the state itself lives in the containment. */
function containerChanged(child: object, property: string | undefined, oldOwner: object | null,
                          newOwner: object | null): void {
    if (property !== undefined) {
        effect({ source: child, property, kind: "set", oldValue: oldOwner, newValue: newOwner }, nothing, nothing);
    }
}

/** The key rule of a keyed containment (`@Keyed(by = "...")`). */
export interface ContainmentKey<TChild> {
    /** The entry property that holds the key. */
    readonly property: string;
    /** Reads the key of an entry. Scalar keys compare by value, model objects by identity. */
    readonly of: (child: TChild) => unknown;
}

/** Options of a containment list. */
export interface ContainmentOptions<TChild> {
    /** Makes the list keyed: every entry has a non-null key that no other entry has. */
    readonly key?: ContainmentKey<TChild>;
}

/**
 * An ordered identity collection that maintains containment when a child moves. A keyed list requires every child
 * to have a key that no other child of the list has, and finds children by key.
 */
export class ContainmentList<TParent extends object, TChild extends object> implements Iterable<TChild> {
    readonly #items: TChild[] = [];
    readonly #attachment: Attachment;
    readonly #key: ContainmentKey<TChild> | undefined;
    #indexed = true;
    /** The owning object; typed as object so that any class implementing the owner's type can share the field. */
    readonly owner: object;

    constructor(owner: TParent, readonly property: string,
                private readonly getLink: (child: TChild) => ContainmentLink, readonly opposite?: string,
                options: ContainmentOptions<TChild> = {}) {
        this.owner = owner;
        this.#key = options.key;
        const key = this.#key;
        this.#attachment = {
            owner,
            property,
            opposite,
            collection: this,
            leave: child => this.#removeAt(this.#items.indexOf(child as TChild)),
            requireKey: key === undefined ? undefined : (child, changed, value) => {
                if (changed === key.property) {
                    this.#requireKeys([], [], child as TChild, value);
                }
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

    indexOf(child: TChild): number {
        return this.#items.indexOf(child);
    }

    contains(child: TChild): boolean {
        return this.#items.includes(child);
    }

    /** The child whose key equals the given key (model keys by identity), or null. Only keyed lists have keys. */
    findByKey(key: unknown): TChild | null {
        if (this.#key === undefined) {
            throw new TypeError(`'${this.property}' is not a keyed containment.`);
        }
        if (key === null || key === undefined) {
            throw new TypeError("A lookup key must not be null.");
        }
        const of = this.#key.of;
        return this.#items.find(child => sameValue(of(child), key)) ?? null;
    }

    /** Appends the child, moving it from its previous container. Returns false if it is already here. */
    add(child: TChild): boolean {
        requireObject(child);
        if (this.contains(child)) {
            return false;
        }
        this.#requireKeys([child], []);
        edit(this.owner, () => this.#attach(this.count, child));
        return true;
    }

    insert(index: number, child: TChild): void {
        requireIndex(index, this.count);
        requireObject(child);
        if (this.contains(child)) {
            throw new Error("The child is already in this containment.");
        }
        this.#requireKeys([child], []);
        edit(this.owner, () => this.#attach(index, child));
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
        this.#requireKeys([child], [previous]);
        edit(this.owner, () => {
            requireNoCycle(this.owner, child);
            const link = this.#link(child);
            leavePrevious(link, child);
            const previousLink = this.getLink(previous);
            const apply = (from: TChild, fromLink: ContainmentLink, to: TChild, toLink: ContainmentLink): void => {
                this.#items[index] = to;
                attachments.delete(fromLink);
                attachments.set(toLink, this.#attachment);
            };
            apply(previous, previousLink, child, link);
            effect({ source: this.owner, property: this.property, kind: "replace", values: [child], replaced: [previous],
                indices: [index] }, () => apply(child, link, previous, previousLink),
            () => apply(previous, previousLink, child, link));
            containerChanged(previous, this.opposite, this.owner, null);
            containerChanged(child, this.opposite, null, this.owner);
        });
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
        edit(this.owner, () => this.#removeAt(index));
        return child;
    }

    clear(): void {
        if (this.count === 0) {
            return;
        }
        edit(this.owner, () => {
            const children = this.#items.splice(0);
            const links = children.map(child => this.getLink(child));
            links.forEach(link => attachments.delete(link));
            effect({ source: this.owner, property: this.property, kind: "remove", values: [...children],
                indices: this.#positions(children.map((_, index) => index)) }, () => {
                this.#items.push(...children);
                links.forEach(link => attachments.set(link, this.#attachment));
            }, () => {
                this.#items.splice(0);
                links.forEach(link => attachments.delete(link));
            });
            children.forEach(child => containerChanged(child, this.opposite, this.owner, null));
        });
    }

    move(oldIndex: number, newIndex: number): void {
        const child = this.at(oldIndex);
        requireIndex(newIndex, this.count - 1);
        if (oldIndex === newIndex) {
            return;
        }
        edit(this.owner, () => {
            this.#items.splice(oldIndex, 1);
            effect({ source: this.owner, property: this.property, kind: "remove", values: [child], indices: [oldIndex] },
                () => { this.#items.splice(oldIndex, 0, child); }, () => { this.#items.splice(oldIndex, 1); });
            this.#items.splice(newIndex, 0, child);
            effect({ source: this.owner, property: this.property, kind: "add", values: [child], indices: [newIndex] },
                () => { this.#items.splice(newIndex, 1); }, () => { this.#items.splice(newIndex, 0, child); });
        });
    }

    toArray(): TChild[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<TChild> {
        return this.#items.values();
    }

    /** @internal Lets an unordered containment present this list as itself; its changes carry no indices. */
    __vmfUnordered(collection: object): void {
        this.#attachment.collection = collection;
        this.#indexed = false;
    }

    #positions(indices: number[]): number[] | undefined {
        return this.#indexed ? indices : undefined;
    }

    /**
     * Rejects incoming children without a key or with a key that another child would share. `outgoing` children
     * leave the list in the same edit. A changed key of an existing child is checked with `changed` and `value`.
     */
    #requireKeys(incoming: readonly TChild[], outgoing: readonly TChild[], changed?: TChild, value?: unknown): void {
        if (this.#key === undefined) {
            return;
        }
        const { property, of } = this.#key;
        if (changed !== undefined && !this.#items.includes(changed)) {
            return;
        }
        const keys = this.#items.filter(child => child !== changed && !outgoing.includes(child)).map(of);
        const check = (key: unknown): void => {
            if (key === null || key === undefined) {
                throw new TypeError(`A keyed entry needs a '${property}'.`);
            }
            if (keys.some(other => sameValue(other, key))) {
                throw new Error(`Another entry already has this '${property}'.`);
            }
            keys.push(key);
        };
        if (changed !== undefined) {
            check(value);
        }
        incoming.forEach(child => check(of(child)));
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
        effect({ source: this.owner, property: this.property, kind: "add", values: [child],
            indices: this.#positions([index]) }, () => {
            this.#items.splice(index, 1);
            attachments.delete(link);
        }, () => {
            this.#items.splice(index, 0, child);
            attachments.set(link, this.#attachment);
        });
        containerChanged(child, this.opposite, null, this.owner);
    }

    #removeAt(index: number): void {
        const child = this.#items[index];
        const link = this.getLink(child);
        if (attachments.get(link) !== this.#attachment) {
            throw new Error("The child and its containment are inconsistent.");
        }
        this.#items.splice(index, 1);
        attachments.delete(link);
        effect({ source: this.owner, property: this.property, kind: "remove", values: [child],
            indices: this.#positions([index]) }, () => {
            this.#items.splice(index, 0, child);
            attachments.set(link, this.#attachment);
        }, () => {
            this.#items.splice(index, 1);
            attachments.delete(link);
        });
        containerChanged(child, this.opposite, this.owner, null);
    }
}

/**
 * An unordered containment (`@Contains Set<T>`): each child once, compared by identity, without positions. A keyed
 * set finds children by key like a keyed list.
 */
export class ContainmentSet<TParent extends object, TChild extends object> implements Iterable<TChild> {
    readonly #children: ContainmentList<TParent, TChild>;
    /** The owning object; typed as object so that any class implementing the owner's type can share the field. */
    readonly owner: object;

    constructor(owner: TParent, readonly property: string, getLink: (child: TChild) => ContainmentLink,
                readonly opposite?: string, options: ContainmentOptions<TChild> = {}) {
        this.owner = owner;
        this.#children = new ContainmentList(owner, property, getLink, opposite, options);
        this.#children.__vmfUnordered(this);
    }

    get count(): number {
        return this.#children.count;
    }

    contains(child: TChild): boolean {
        return this.#children.contains(child);
    }

    /** The child whose key equals the given key (model keys by identity), or null. */
    findByKey(key: unknown): TChild | null {
        return this.#children.findByKey(key);
    }

    /** Adds the child, moving it from its previous container. Returns false if it is already here. */
    add(child: TChild): boolean {
        return this.#children.add(child);
    }

    remove(child: TChild): boolean {
        return this.#children.remove(child);
    }

    clear(): void {
        this.#children.clear();
    }

    toArray(): TChild[] {
        return this.#children.toArray();
    }

    [Symbol.iterator](): IterableIterator<TChild> {
        return this.#children[Symbol.iterator]();
    }
}

/** A single-valued containment property. */
export class ContainmentSlot<TParent extends object, TChild extends object> {
    #value: TChild | null = null;
    readonly #attachment: Attachment;
    /** The owning object; typed as object so that any class implementing the owner's type can share the field. */
    readonly owner: object;

    constructor(owner: TParent, readonly property: string,
                private readonly getLink: (child: TChild) => ContainmentLink, readonly opposite?: string) {
        this.owner = owner;
        this.#attachment = {
            owner,
            property,
            opposite,
            collection: this,
            leave: child => {
                if (child === this.#value) {
                    this.#assign(null);
                }
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
        if (child !== null) {
            requireObject(child);
        }
        edit(this.owner, () => {
            if (child !== null) {
                requireNoCycle(this.owner, child);
                const link = this.getLink(child);
                if (!(link instanceof ContainmentLink)) {
                    throw new TypeError("The child does not have a VMF containment link.");
                }
                leavePrevious(link, child);
            }
            this.#assign(child);
        });
    }

    #assign(child: TChild | null): void {
        const previous = this.#value;
        if (previous === child) {
            return;
        }
        const previousLink = previous === null ? undefined : this.getLink(previous);
        const link = child === null ? undefined : this.getLink(child);
        const apply = (to: TChild | null, fromLink: ContainmentLink | undefined, toLink: ContainmentLink | undefined): void => {
            this.#value = to;
            if (fromLink !== undefined) {
                attachments.delete(fromLink);
            }
            if (toLink !== undefined) {
                attachments.set(toLink, this.#attachment);
            }
        };
        apply(child, previousLink, link);
        effect({ source: this.owner, property: this.property, kind: "set", oldValue: previous, newValue: child },
            () => apply(previous, link, previousLink), () => apply(child, previousLink, link));
        if (previous !== null) {
            containerChanged(previous, this.opposite, this.owner, null);
        }
        if (child !== null) {
            containerChanged(child, this.opposite, null, this.owner);
        }
    }
}

/**
 * An unordered collection of distinct model references, compared by identity. With an opposite, the opposite
 * property of every member points back to the owner.
 */
export class ReferenceSet<T extends object> implements Iterable<T> {
    readonly #items = new Set<T>();

    constructor(readonly owner: object, readonly property: string, private readonly opposite?: string) {
    }

    get count(): number {
        return this.#items.size;
    }

    add(item: T): boolean {
        requireObject(item);
        if (this.#items.has(item)) {
            return false;
        }
        edit(this.owner, () => {
            if (this.opposite !== undefined) {
                linkFrom(item, this.opposite, this.owner, this.property);
            }
            this.#add(item);
        });
        return true;
    }

    remove(item: T): boolean {
        if (!this.#items.has(item)) {
            return false;
        }
        edit(this.owner, () => {
            this.#delete(item);
            if (this.opposite !== undefined) {
                unlink(item, this.opposite, this.owner);
            }
        });
        return true;
    }

    contains(item: T): boolean {
        return this.#items.has(item);
    }

    clear(): void {
        if (this.#items.size === 0) {
            return;
        }
        edit(this.owner, () => {
            const items = [...this.#items];
            this.#items.clear();
            effect({ source: this.owner, property: this.property, kind: "remove", values: items },
                () => items.forEach(item => this.#items.add(item)), () => { this.#items.clear(); });
            if (this.opposite !== undefined) {
                for (const item of items) {
                    unlink(item, this.opposite, this.owner);
                }
            }
        });
    }

    toArray(): T[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }

    /** @internal Adds as part of the active edit, without touching the opposite. */
    __vmfLinkAppend(item: object): void {
        if (!this.#items.has(item as T)) {
            this.#add(item as T);
        }
    }

    /** @internal Removes as part of the active edit, without touching the opposite. */
    __vmfLinkRemove(item: object): void {
        if (this.#items.has(item as T)) {
            this.#delete(item as T);
        }
    }

    /** @internal Adds outside of any edit, for copies whose opposite ends are copied too. */
    __vmfAppendRaw(item: object): void {
        this.#items.add(item as T);
    }

    #add(item: T): void {
        this.#items.add(item);
        effect({ source: this.owner, property: this.property, kind: "add", values: [item] },
            () => { this.#items.delete(item); }, () => { this.#items.add(item); });
    }

    #delete(item: T): void {
        this.#items.delete(item);
        effect({ source: this.owner, property: this.property, kind: "remove", values: [item] },
            () => { this.#items.add(item); }, () => { this.#items.delete(item); });
    }
}

/** Storage of a single-valued reference that has an opposite. Change it through the owning property only. */
export class ReferenceSlot<T extends object> {
    #value: T | null = null;

    constructor(readonly owner: object, readonly property: string) {
    }

    get value(): T | null {
        return this.#value;
    }

    /** @internal Stores a value as part of the active edit, without touching the opposite. */
    __vmfAssign(value: object | null): void {
        const previous = this.#value;
        if (previous === value) {
            return;
        }
        this.#value = value as T | null;
        effect({ source: this.owner, property: this.property, kind: "set", oldValue: previous, newValue: value },
            () => { this.#value = previous; }, () => { this.#value = value as T | null; });
    }

    /** @internal Stores a value outside of any edit, for copies whose opposite ends are copied too. */
    __vmfInitialize(value: object | null): void {
        this.#value = value as T | null;
    }
}

/** A many-valued reference end: a reference list or set that can be linked without touching its own opposite. */
interface ReferenceMany {
    contains(item: object): boolean;
    __vmfLinkAppend(item: object): void;
    __vmfLinkRemove(item: object): void;
}

type ReferenceEnd = { readonly slot: ReferenceSlot<object> } | { readonly many: ReferenceMany };

function endOf(model: object, property: string): ReferenceEnd {
    const members = model as Record<string, unknown>;
    const slot = members["__vmfRef_" + property];
    if (slot instanceof ReferenceSlot) {
        return { slot };
    }
    const many = members[property];
    if (many instanceof ReferenceList || many instanceof ReferenceSet) {
        return { many: many as ReferenceMany };
    }
    throw new TypeError(`'${property}' is not a reference with an opposite.`);
}

function unlink(target: object, opposite: string, owner: object): void {
    const end = endOf(target, opposite);
    if ("slot" in end) {
        if (end.slot.value === owner) {
            end.slot.__vmfAssign(null);
        }
    } else {
        end.many.__vmfLinkRemove(owner);
    }
}

function link(target: object, opposite: string, owner: object): void {
    const end = endOf(target, opposite);
    if ("slot" in end) {
        end.slot.__vmfAssign(owner);
    } else if (!end.many.contains(owner)) {
        end.many.__vmfLinkAppend(owner);
    }
}

/**
 * Points the item's opposite to the owner of a many-valued end as part of the active edit. A single-valued opposite
 * leaves its previous owner, whose end (`property`) loses the item.
 */
function linkFrom(item: object, opposite: string, owner: object, property: string): void {
    const end = endOf(item, opposite);
    if ("slot" in end) {
        const previous = end.slot.value;
        if (previous !== null && previous !== owner) {
            const previousEnd = endOf(previous, property);
            if ("many" in previousEnd) {
                previousEnd.many.__vmfLinkRemove(item);
            }
        }
        end.slot.__vmfAssign(owner);
    } else if (!end.many.contains(owner)) {
        end.many.__vmfLinkAppend(owner);
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
    }
    edit(owner, () => {
        if (value !== null) {
            const target = endOf(value, opposite);
            if ("slot" in target && target.slot.value !== null && target.slot.value !== owner) {
                const partner = endOf(target.slot.value, property);
                if ("slot" in partner) {
                    partner.slot.__vmfAssign(null);
                }
            }
        }
        if (previous !== null) {
            unlink(previous, opposite, owner);
        }
        own.slot.__vmfAssign(value);
        if (value !== null) {
            link(value, opposite, owner);
        }
    });
}

/**
 * Sets a single-valued scalar or plain reference property stored as `__vmfValue_<property>`. The key of an entry in
 * a keyed containment cannot become `null` or the key of another entry.
 */
export function setValue(owner: object, property: string, value: unknown): void {
    const storage = owner as Record<string, unknown>;
    const key = "__vmfValue_" + property;
    const previous = storage[key];
    if (Object.is(previous, value)) {
        return;
    }
    const link = linkOf(owner);
    if (link !== undefined) {
        attachments.get(link)?.requireKey?.(owner, property, value);
    }
    edit(owner, () => {
        storage[key] = value;
        effect({ source: owner, property, kind: "set", oldValue: previous, newValue: value },
            () => { storage[key] = previous; }, () => { storage[key] = value; });
    });
}

/** Options of a reference list. */
export interface ReferenceListOptions {
    /** Each object occurs at most once (an ordered set, `@Multiplicity(kind = ORDERED_SET)`). */
    readonly unique?: boolean;
}

/**
 * An ordered list of model references. Without an opposite it may hold the same object several times unless it is
 * unique (an ordered set); with an opposite each object occurs once and the opposite property of every member points
 * back to the owner.
 */
export class ReferenceList<T extends object> implements Iterable<T> {
    readonly #items: T[] = [];
    readonly #unique: boolean;

    constructor(readonly owner: object, readonly property: string, private readonly opposite?: string,
                options: ReferenceListOptions = {}) {
        this.#unique = options.unique === true || opposite !== undefined;
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

    /** Appends the item. Returns false if a unique list already holds it. */
    add(item: T): boolean {
        requireObject(item);
        if (this.opposite !== undefined ? this.#linked(item) : this.#unique && this.contains(item)) {
            return false;
        }
        edit(this.owner, () => {
            if (this.opposite !== undefined) {
                linkFrom(item, this.opposite, this.owner, this.property);
            }
            this.#insert(this.count, item);
        });
        return true;
    }

    insert(index: number, item: T): void {
        requireIndex(index, this.count);
        requireObject(item);
        if (this.#unique && this.contains(item)) {
            throw new Error("The reference is already in this list.");
        }
        edit(this.owner, () => {
            if (this.opposite !== undefined) {
                linkFrom(item, this.opposite, this.owner, this.property);
            }
            this.#insert(index, item);
        });
    }

    set(index: number, item: T): T {
        requireIndex(index, this.count - 1);
        requireObject(item);
        const previous = this.#items[index];
        if (previous === item) {
            return previous;
        }
        if (this.#unique && this.contains(item)) {
            throw new Error("The reference is already in this list.");
        }
        if (this.opposite === undefined) {
            edit(this.owner, () => {
                this.#items[index] = item;
                effect({ source: this.owner, property: this.property, kind: "replace", values: [item],
                    replaced: [previous], indices: [index] },
                () => { this.#items[index] = previous; }, () => { this.#items[index] = item; });
            });
            return previous;
        }
        edit(this.owner, () => {
            this.#removeAt(index);
            linkFrom(item, this.opposite!, this.owner, this.property);
            this.#insert(index, item);
        });
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
        const item = this.#items[index];
        edit(this.owner, () => this.#removeAt(index));
        return item;
    }

    clear(): void {
        if (this.count === 0) {
            return;
        }
        edit(this.owner, () => {
            const items = this.#items.splice(0);
            effect({ source: this.owner, property: this.property, kind: "remove", values: [...items],
                indices: items.map((_, index) => index) },
            () => { this.#items.push(...items); }, () => { this.#items.splice(0); });
            if (this.opposite !== undefined) {
                for (const item of items) {
                    unlink(item, this.opposite, this.owner);
                }
            }
        });
    }

    toArray(): T[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }

    /** @internal Appends as part of the active edit, without touching the opposite. */
    __vmfLinkAppend(item: object): void {
        this.#insert(this.count, item as T);
    }

    /** @internal Removes the first occurrence as part of the active edit, without touching the opposite. */
    __vmfLinkRemove(item: object): void {
        const index = this.#items.indexOf(item as T);
        if (index >= 0) {
            this.#items.splice(index, 1);
            effect({ source: this.owner, property: this.property, kind: "remove", values: [item], indices: [index] },
                () => { this.#items.splice(index, 0, item as T); }, () => { this.#items.splice(index, 1); });
        }
    }

    /** @internal Appends outside of any edit, for copies whose opposite ends are copied too. */
    __vmfAppendRaw(item: object): void {
        this.#items.push(item as T);
    }

    #insert(index: number, item: T): void {
        this.#items.splice(index, 0, item);
        effect({ source: this.owner, property: this.property, kind: "add", values: [item], indices: [index] },
            () => { this.#items.splice(index, 1); }, () => { this.#items.splice(index, 0, item); });
    }

    #removeAt(index: number): void {
        const [item] = this.#items.splice(index, 1);
        effect({ source: this.owner, property: this.property, kind: "remove", values: [item], indices: [index] },
            () => { this.#items.splice(index, 0, item); }, () => { this.#items.splice(index, 1); });
        if (this.opposite !== undefined) {
            unlink(item, this.opposite, this.owner);
        }
    }

    /** True if the item's opposite already points to this owner. */
    #linked(item: T): boolean {
        const end = endOf(item, this.opposite!);
        return "slot" in end ? end.slot.value === this.owner : this.contains(item);
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
