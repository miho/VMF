import { sameValue } from "./collections.js";
import { insertAt, inserted, memberAdded, memberRemoved, removeAt, removed, replaced } from "./effects.js";
import {
    __vmfBegin as begin, __vmfEffect as effect, __vmfEnd as end, __vmfRequireEditable as requireEditable,
    __vmfUseContainers, __vmfWatched as watched, __vmfWatching as watching, type Frame, type Watch,
} from "./changes.js";

type ModelClass<T> = abstract new (...args: never[]) => T;

/** The place a contained child occupies: the containment list or slot of its owner that holds it. */
interface Attachment {
    readonly owner: object;
    readonly property: string;
    /** The child's container property that names this containment, if any. */
    readonly opposite?: string;
    /** The collection that shows the child: the place itself, or the unordered containment built on it. */
    readonly __vmfCollection: object;
    /** Removes the child, because it moves elsewhere or leaves its container. */
    __vmfLeave(child: object, frame: Frame | undefined): void;
    /** Rejects a change of the child's key property that a keyed containment does not allow. */
    __vmfRequireKey?(child: object, property: string, value: unknown): void;
}

/** Tracks a child's single containing relationship across owner and child model types. */
export class ContainmentLink {
    /** @internal Where the child is contained; undefined while it has no container. */
    __vmfAttachment: Attachment | undefined = undefined;
    /** @internal The listeners and the recording of the child, which the change machinery keeps here. */
    __vmfWatch: Watch | undefined = undefined;

    get owner(): object | undefined {
        return this.__vmfAttachment?.owner;
    }

    get collection(): object | undefined {
        return this.__vmfAttachment?.__vmfCollection;
    }

    /** The model name of the containment property that holds the child. */
    get property(): string | undefined {
        return this.__vmfAttachment?.property;
    }

    /** Removes the child from its container. */
    detach(child: object): boolean {
        const attachment = this.__vmfAttachment;
        if (attachment === undefined) {
            return false;
        }
        const frame = begin(child, watched(child));
        attachment.__vmfLeave(child, frame);
        end(frame);
        return true;
    }
}

/** Returns the owner if it is an instance of the owner type and holds the child in one of the given properties. */
export function containerOf<T extends object>(link: ContainmentLink, ownerType: ModelClass<T>,
                                              properties: readonly string[]): T | null {
    const attachment = link.__vmfAttachment;
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

__vmfUseContainers(linkOf);

/** Rejects an edit that would make the child contain its new owner. */
function requireNoCycle(owner: object, child: object): void {
    for (let current: object | undefined = owner; current !== undefined; current = linkOf(current)?.owner) {
        if (current === child) {
            throw new Error("The containment edit would create a cycle.");
        }
    }
}

/** Registers a change of the child's container property; the state itself lives in the containment. */
function containerChanged(frame: Frame, child: object, property: string | undefined, oldOwner: object | null,
                          newOwner: object | null): void {
    if (property !== undefined) {
        effect(frame, { source: child, property, kind: "set", oldValue: oldOwner, newValue: newOwner }, nothing, nothing);
    }
}

function nothing(): void {
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
    readonly #key: ContainmentKey<TChild> | undefined;
    #indexed = true;
    /** @internal The collection that shows the children: this list, or the unordered containment built on it. */
    __vmfCollection: object = this;
    /** The owning object; typed as object so that any class implementing the owner's type can share the field. */
    readonly owner: object;

    constructor(owner: TParent, readonly property: string,
                private readonly getLink: (child: TChild) => ContainmentLink, readonly opposite?: string,
                options?: ContainmentOptions<TChild>) {
        this.owner = owner;
        this.#key = options?.key;
    }

    get count(): number {
        return this.#items.length;
    }

    at(index: number): TChild {
        requireIndex(index, this.#items.length - 1);
        return this.#items[index];
    }

    indexOf(child: TChild): number {
        return this.#items.indexOf(child);
    }

    /** True if the child is in this list. Its containment link tells, so the answer does not depend on the size. */
    contains(child: TChild): boolean {
        return typeof child === "object" && child !== null && linkOf(child)?.__vmfAttachment === this;
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
        this.#attach(this.#items.length, child);
        return true;
    }

    insert(index: number, child: TChild): void {
        requireIndex(index, this.#items.length);
        requireObject(child);
        if (this.contains(child)) {
            throw new Error("The child is already in this containment.");
        }
        this.#attach(index, child);
    }

    /** Replaces the child at the index and releases the previous one. */
    set(index: number, child: TChild): TChild {
        requireIndex(index, this.#items.length - 1);
        requireObject(child);
        const previous = this.#items[index];
        if (previous === child) {
            return previous;
        }
        if (this.contains(child)) {
            throw new Error("The child is already in this containment.");
        }
        if (this.#key !== undefined) {
            this.#requireKeys([child], [previous]);
        }
        // The previous child is in this list: whoever observes the owner observes it too.
        const frame = begin(this.owner, watched(previous) || watched(child));
        requireNoCycle(this.owner, child);
        const link = this.#link(child);
        link.__vmfAttachment?.__vmfLeave(child, frame);
        const previousLink = this.getLink(previous);
        this.#items[index] = child;
        previousLink.__vmfAttachment = undefined;
        link.__vmfAttachment = this;
        if (frame !== undefined) {
            exchanged(frame, this, this.#items, index, previous, previousLink, child, link);
            containerChanged(frame, previous, this.opposite, this.owner, null);
            containerChanged(frame, child, this.opposite, null, this.owner);
        }
        end(frame);
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
        requireIndex(index, this.#items.length - 1);
        const child = this.#items[index];
        const frame = begin(this.owner, watched(child));
        this.#removeAt(index, frame);
        end(frame);
        return child;
    }

    clear(): void {
        if (this.#items.length === 0) {
            return;
        }
        const frame = begin(this.owner, watching());
        const children = this.#items.splice(0);
        const links = children.map(child => this.getLink(child));
        for (const link of links) {
            link.__vmfAttachment = undefined;
        }
        if (frame !== undefined) {
            effect(frame, { source: this.owner, property: this.property, kind: "remove", values: [...children],
                indices: this.#positions(children.map((_, index) => index)) }, () => {
                this.#items.push(...children);
                links.forEach(link => { link.__vmfAttachment = this; });
            }, () => {
                this.#items.splice(0);
                links.forEach(link => { link.__vmfAttachment = undefined; });
            });
            children.forEach(child => containerChanged(frame, child, this.opposite, this.owner, null));
        }
        end(frame);
    }

    move(oldIndex: number, newIndex: number): void {
        const child = this.at(oldIndex);
        requireIndex(newIndex, this.#items.length - 1);
        if (oldIndex === newIndex) {
            return;
        }
        const frame = begin(this.owner, watched(this.owner));
        removeAt(this.#items, oldIndex);
        insertAt(this.#items, newIndex, child);
        if (frame !== undefined) {
            removed(frame, this.owner, this.property, this.#items, oldIndex, child);
            inserted(frame, this.owner, this.property, this.#items, newIndex, child);
        }
        end(frame);
    }

    toArray(): TChild[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<TChild> {
        return this.#items.values();
    }

    /** @internal Lets an unordered containment present this list as itself; its changes carry no indices. */
    __vmfUnordered(collection: object): void {
        this.__vmfCollection = collection;
        this.#indexed = false;
    }

    /** @internal Removes the child as part of the change in progress. */
    __vmfLeave(child: object, frame: Frame | undefined): void {
        this.#removeAt(this.#items.indexOf(child as TChild), frame);
    }

    /** @internal Rejects a key of the child that a keyed list does not allow. */
    __vmfRequireKey(child: object, property: string, value: unknown): void {
        if (this.#key !== undefined && property === this.#key.property) {
            this.#requireKeys([], [], child as TChild, value);
        }
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

    /** Takes a child that is not in this list, from its previous container if it has one. */
    #attach(index: number, child: TChild): void {
        if (this.#key !== undefined) {
            this.#requireKeys([child], []);
        }
        const frame = begin(this.owner, watched(this.owner) || watched(child));
        requireNoCycle(this.owner, child);
        const link = this.#link(child);
        link.__vmfAttachment?.__vmfLeave(child, frame);
        insertAt(this.#items, index, child);
        link.__vmfAttachment = this;
        if (frame !== undefined) {
            attached(frame, this, this.#items, index, child, link, this.#positions([index]));
            containerChanged(frame, child, this.opposite, null, this.owner);
        }
        end(frame);
    }

    #removeAt(index: number, frame: Frame | undefined): void {
        const child = this.#items[index];
        const link = this.getLink(child);
        if (link.__vmfAttachment !== this) {
            throw new Error("The child and its containment are inconsistent.");
        }
        removeAt(this.#items, index);
        link.__vmfAttachment = undefined;
        if (frame !== undefined) {
            detached(frame, this, this.#items, index, child, link, this.#positions([index]));
            containerChanged(frame, child, this.opposite, this.owner, null);
        }
    }
}

function attached<TChild extends object>(frame: Frame, place: Attachment, items: TChild[], index: number, child: TChild,
                                         link: ContainmentLink, indices: number[] | undefined): void {
    effect(frame, { source: place.owner, property: place.property, kind: "add", values: [child], indices }, () => {
        removeAt(items, index);
        link.__vmfAttachment = undefined;
    }, () => {
        insertAt(items, index, child);
        link.__vmfAttachment = place;
    });
}

function detached<TChild extends object>(frame: Frame, place: Attachment, items: TChild[], index: number, child: TChild,
                                         link: ContainmentLink, indices: number[] | undefined): void {
    effect(frame, { source: place.owner, property: place.property, kind: "remove", values: [child], indices }, () => {
        insertAt(items, index, child);
        link.__vmfAttachment = place;
    }, () => {
        removeAt(items, index);
        link.__vmfAttachment = undefined;
    });
}

function exchanged<TChild extends object>(frame: Frame, place: Attachment, items: TChild[], index: number,
                                          previous: TChild, previousLink: ContainmentLink, child: TChild,
                                          link: ContainmentLink): void {
    effect(frame, { source: place.owner, property: place.property, kind: "replace", values: [child],
        replaced: [previous], indices: [index] }, () => {
        items[index] = previous;
        link.__vmfAttachment = undefined;
        previousLink.__vmfAttachment = place;
    }, () => {
        items[index] = child;
        previousLink.__vmfAttachment = undefined;
        link.__vmfAttachment = place;
    });
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
                readonly opposite?: string, options?: ContainmentOptions<TChild>) {
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
    /** The owning object; typed as object so that any class implementing the owner's type can share the field. */
    readonly owner: object;

    constructor(owner: TParent, readonly property: string,
                private readonly getLink: (child: TChild) => ContainmentLink, readonly opposite?: string) {
        this.owner = owner;
    }

    get value(): TChild | null {
        return this.#value;
    }

    /** @internal The collection that shows the child: the slot itself. */
    get __vmfCollection(): object {
        return this;
    }

    /** Stores the child, moving it from its previous container and releasing the current child. */
    set(child: TChild | null): void {
        const previous = this.#value;
        if (child === previous) {
            return;
        }
        if (child !== null) {
            requireObject(child);
        }
        // The current child is in this slot: whoever observes the owner observes it too.
        const frame = begin(this.owner, watched(previous ?? this.owner) || (child !== null && watched(child)));
        if (child !== null) {
            requireNoCycle(this.owner, child);
            const link = this.getLink(child);
            if (!(link instanceof ContainmentLink)) {
                throw new TypeError("The child does not have a VMF containment link.");
            }
            link.__vmfAttachment?.__vmfLeave(child, frame);
        }
        this.#assign(child, frame);
        end(frame);
    }

    /** @internal Releases the child as part of the change in progress. */
    __vmfLeave(child: object, frame: Frame | undefined): void {
        if (child === this.#value) {
            this.#assign(null, frame);
        }
    }

    #assign(child: TChild | null, frame: Frame | undefined): void {
        const previous = this.#value;
        if (previous === child) {
            return;
        }
        const previousLink = previous === null ? undefined : this.getLink(previous);
        const link = child === null ? undefined : this.getLink(child);
        this.#value = child;
        if (previousLink !== undefined) {
            previousLink.__vmfAttachment = undefined;
        }
        if (link !== undefined) {
            link.__vmfAttachment = this;
        }
        if (frame !== undefined) {
            ContainmentSlot.#assigned(frame, this, previous, previousLink, child, link);
            if (previous !== null) {
                containerChanged(frame, previous, this.opposite, this.owner, null);
            }
            if (child !== null) {
                containerChanged(frame, child, this.opposite, null, this.owner);
            }
        }
    }

    static #assigned<TParent extends object, TChild extends object>(
        frame: Frame, slot: ContainmentSlot<TParent, TChild>, previous: TChild | null,
        previousLink: ContainmentLink | undefined, child: TChild | null, link: ContainmentLink | undefined): void {
        const apply = (to: TChild | null, from: ContainmentLink | undefined, toLink: ContainmentLink | undefined): void => {
            slot.#value = to;
            if (from !== undefined) {
                from.__vmfAttachment = undefined;
            }
            if (toLink !== undefined) {
                toLink.__vmfAttachment = slot;
            }
        };
        effect(frame, { source: slot.owner, property: slot.property, kind: "set", oldValue: previous, newValue: child },
            () => apply(previous, link, previousLink), () => apply(child, previousLink, link));
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
        const frame = begin(this.owner, this.#watched());
        if (this.opposite !== undefined) {
            linkFrom(item, this.opposite, this.owner, this.property, frame);
        }
        this.#add(item, frame);
        end(frame);
        return true;
    }

    remove(item: T): boolean {
        if (!this.#items.has(item)) {
            return false;
        }
        const frame = begin(this.owner, this.#watched());
        this.#delete(item, frame);
        if (this.opposite !== undefined) {
            unlink(item, this.opposite, this.owner, frame);
        }
        end(frame);
        return true;
    }

    contains(item: T): boolean {
        return this.#items.has(item);
    }

    clear(): void {
        if (this.#items.size === 0) {
            return;
        }
        const frame = begin(this.owner, this.#watched());
        const items = [...this.#items];
        this.#items.clear();
        if (frame !== undefined) {
            effect(frame, { source: this.owner, property: this.property, kind: "remove", values: items },
                () => items.forEach(item => this.#items.add(item)), () => { this.#items.clear(); });
        }
        if (this.opposite !== undefined) {
            for (const item of items) {
                unlink(item, this.opposite, this.owner, frame);
            }
        }
        end(frame);
    }

    toArray(): T[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }

    /** @internal Adds as part of the change in progress, without touching the opposite. */
    __vmfLinkAppend(item: object, frame: Frame | undefined): void {
        if (!this.#items.has(item as T)) {
            this.#add(item as T, frame);
        }
    }

    /** @internal Removes as part of the change in progress, without touching the opposite. */
    __vmfLinkRemove(item: object, frame: Frame | undefined): void {
        if (this.#items.has(item as T)) {
            this.#delete(item as T, frame);
        }
    }

    /** @internal Adds outside of any edit, for copies whose opposite ends are copied too. */
    __vmfAppendRaw(item: object): void {
        this.#items.add(item as T);
    }

    /** A change of a set with an opposite also changes its members, which anybody may observe. */
    #watched(): boolean {
        return this.opposite === undefined ? watched(this.owner) : watching();
    }

    #add(item: T, frame: Frame | undefined): void {
        this.#items.add(item);
        if (frame !== undefined) {
            memberAdded(frame, this.owner, this.property, this.#items, item);
        }
    }

    #delete(item: T, frame: Frame | undefined): void {
        this.#items.delete(item);
        if (frame !== undefined) {
            memberRemoved(frame, this.owner, this.property, this.#items, item);
        }
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

    /** @internal Stores a value as part of the change in progress, without touching the opposite. */
    __vmfAssign(value: object | null, frame: Frame | undefined): void {
        const previous = this.#value;
        if (previous === value) {
            return;
        }
        this.#value = value as T | null;
        if (frame !== undefined) {
            ReferenceSlot.#assigned(frame, this, previous, value as T | null);
        }
    }

    /** @internal Stores a value outside of any edit, for copies whose opposite ends are copied too. */
    __vmfInitialize(value: object | null): void {
        this.#value = value as T | null;
    }

    static #assigned<T extends object>(frame: Frame, slot: ReferenceSlot<T>, previous: T | null, value: T | null): void {
        effect(frame, { source: slot.owner, property: slot.property, kind: "set", oldValue: previous, newValue: value },
            () => { slot.#value = previous; }, () => { slot.#value = value; });
    }
}

/** A many-valued reference end: a reference list or set that can be linked without touching its own opposite. */
interface ReferenceMany {
    contains(item: object): boolean;
    __vmfLinkAppend(item: object, frame: Frame | undefined): void;
    __vmfLinkRemove(item: object, frame: Frame | undefined): void;
}

type ReferenceEnd = ReferenceSlot<object> | ReferenceMany;

/** The names of the fields that hold single-valued references, by property name. */
const slotFields = new Map<string, string>();

/** @internal The slot of a single-valued reference with an opposite, which generated code keeps in a field. */
export function __vmfSlotOf(model: object, property: string): ReferenceSlot<object> {
    let field = slotFields.get(property);
    if (field === undefined) {
        field = "__vmfRef_" + property;
        slotFields.set(property, field);
    }
    return (model as Record<string, ReferenceSlot<object>>)[field];
}

/** The end of a reference with an opposite: the slot of a single-valued property or its list or set. */
function endOf(model: object, property: string): ReferenceEnd {
    const members = model as Record<string, unknown>;
    const slot: unknown = __vmfSlotOf(model, property);
    if (slot instanceof ReferenceSlot) {
        return slot;
    }
    const many = members[property];
    if (many instanceof ReferenceList || many instanceof ReferenceSet) {
        return many as ReferenceMany;
    }
    throw new TypeError(`'${property}' is not a reference with an opposite.`);
}

function unlink(target: object, opposite: string, owner: object, frame: Frame | undefined): void {
    const end = endOf(target, opposite);
    if (end instanceof ReferenceSlot) {
        if (end.value === owner) {
            end.__vmfAssign(null, frame);
        }
    } else {
        end.__vmfLinkRemove(owner, frame);
    }
}

/**
 * Points the item's opposite to the owner of a many-valued end as part of the change in progress. A single-valued
 * opposite leaves its previous owner, whose end (`property`) loses the item.
 */
function linkFrom(item: object, opposite: string, owner: object, property: string, frame: Frame | undefined): void {
    const end = endOf(item, opposite);
    if (end instanceof ReferenceSlot) {
        const previous = end.value;
        if (previous !== null && previous !== owner) {
            const previousEnd = endOf(previous, property);
            if (!(previousEnd instanceof ReferenceSlot)) {
                previousEnd.__vmfLinkRemove(item, frame);
            }
        }
        end.__vmfAssign(owner, frame);
    } else if (!end.contains(owner)) {
        end.__vmfLinkAppend(owner, frame);
    }
}

/**
 * Sets the single-valued reference of a slot and keeps both ends consistent: the old target loses its opposite, and
 * in a one-to-one pair the new target's previous partner loses its reference.
 */
export function setReference(own: ReferenceSlot<object>, value: object | null, opposite: string): void {
    const previous = own.value;
    if (previous === value) {
        return;
    }
    if (value !== null) {
        requireObject(value);
    }
    const owner = own.owner;
    requireEditable();
    const target = value === null ? undefined : endOf(value, opposite);
    const partner = target instanceof ReferenceSlot ? target.value : null;
    // The old target, the new target and its previous partner change too.
    const frame = begin(owner, watched(owner) || (previous !== null && watched(previous))
        || (value !== null && watched(value)) || (partner !== null && watched(partner)));
    if (partner !== null && partner !== owner) {
        const partnerEnd = endOf(partner, own.property);
        if (partnerEnd instanceof ReferenceSlot) {
            partnerEnd.__vmfAssign(null, frame);
        }
    }
    if (previous !== null) {
        unlink(previous, opposite, owner, frame);
    }
    own.__vmfAssign(value, frame);
    if (target instanceof ReferenceSlot) {
        target.__vmfAssign(owner, frame);
    } else if (target !== undefined && !target.contains(owner)) {
        target.__vmfLinkAppend(owner, frame);
    }
    end(frame);
}

/** The names of the fields that hold single values, by property name. */
const valueFields = new Map<string, string>();

function valueField(property: string): string {
    let field = valueFields.get(property);
    if (field === undefined) {
        field = "__vmfValue_" + property;
        valueFields.set(property, field);
    }
    return field;
}

/**
 * A generated setter of a single-valued scalar or plain reference property calls this before it stores a value
 * that differs from the current one in its field, `__vmfValue_<property>`. Rejects the change where it is not
 * allowed: the key of an entry in a keyed containment cannot become `null` or the key of another entry.
 */
export function willSet(link: ContainmentLink, owner: object, property: string, value: unknown): void {
    const attachment = link.__vmfAttachment;
    if (attachment !== undefined) {
        attachment.__vmfRequireKey?.(owner, property, value);
    }
    requireEditable();
}

/** The setter calls this after it stored the value: it reports the change. */
export function didSet(owner: object, property: string, previous: unknown, value: unknown): void {
    const frame = begin(owner, watched(owner));
    if (frame !== undefined) {
        valueSet(frame, owner, property, previous, value);
        end(frame);
    }
}

function valueSet(frame: Frame, owner: object, property: string, previous: unknown, value: unknown): void {
    const storage = owner as Record<string, unknown>;
    effect(frame, { source: owner, property, kind: "set", oldValue: previous, newValue: value },
        () => { storage[valueField(property)] = previous; }, () => { storage[valueField(property)] = value; });
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
                options?: ReferenceListOptions) {
        this.#unique = options?.unique === true || opposite !== undefined;
    }

    get count(): number {
        return this.#items.length;
    }

    at(index: number): T {
        requireIndex(index, this.#items.length - 1);
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
        const frame = begin(this.owner, this.#watched());
        if (this.opposite !== undefined) {
            linkFrom(item, this.opposite, this.owner, this.property, frame);
        }
        this.#insert(this.#items.length, item, frame);
        end(frame);
        return true;
    }

    insert(index: number, item: T): void {
        requireIndex(index, this.#items.length);
        requireObject(item);
        if (this.#unique && this.contains(item)) {
            throw new Error("The reference is already in this list.");
        }
        const frame = begin(this.owner, this.#watched());
        if (this.opposite !== undefined) {
            linkFrom(item, this.opposite, this.owner, this.property, frame);
        }
        this.#insert(index, item, frame);
        end(frame);
    }

    set(index: number, item: T): T {
        requireIndex(index, this.#items.length - 1);
        requireObject(item);
        const previous = this.#items[index];
        if (previous === item) {
            return previous;
        }
        if (this.#unique && this.contains(item)) {
            throw new Error("The reference is already in this list.");
        }
        if (this.opposite === undefined) {
            const frame = begin(this.owner, watched(this.owner));
            this.#items[index] = item;
            if (frame !== undefined) {
                replaced(frame, this.owner, this.property, this.#items, index, previous, item);
            }
            end(frame);
            return previous;
        }
        const frame = begin(this.owner, watching());
        // Before anything changes: fails for an item without the opposite property.
        endOf(item, this.opposite);
        this.#removeAt(index, frame);
        linkFrom(item, this.opposite, this.owner, this.property, frame);
        this.#insert(index, item, frame);
        end(frame);
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
        requireIndex(index, this.#items.length - 1);
        const item = this.#items[index];
        const frame = begin(this.owner, this.#watched());
        this.#removeAt(index, frame);
        end(frame);
        return item;
    }

    clear(): void {
        if (this.#items.length === 0) {
            return;
        }
        const frame = begin(this.owner, this.#watched());
        const items = this.#items.splice(0);
        if (frame !== undefined) {
            effect(frame, { source: this.owner, property: this.property, kind: "remove", values: [...items],
                indices: items.map((_, index) => index) },
            () => { this.#items.push(...items); }, () => { this.#items.splice(0); });
        }
        if (this.opposite !== undefined) {
            for (const item of items) {
                unlink(item, this.opposite, this.owner, frame);
            }
        }
        end(frame);
    }

    toArray(): T[] {
        return [...this.#items];
    }

    [Symbol.iterator](): IterableIterator<T> {
        return this.#items.values();
    }

    /** @internal Appends as part of the change in progress, without touching the opposite. */
    __vmfLinkAppend(item: object, frame: Frame | undefined): void {
        this.#insert(this.#items.length, item as T, frame);
    }

    /** @internal Removes the first occurrence as part of the change in progress, without touching the opposite. */
    __vmfLinkRemove(item: object, frame: Frame | undefined): void {
        const index = this.#items.indexOf(item as T);
        if (index >= 0) {
            removeAt(this.#items, index);
            if (frame !== undefined) {
                removed(frame, this.owner, this.property, this.#items, index, item as T);
            }
        }
    }

    /** @internal Appends outside of any edit, for copies whose opposite ends are copied too. */
    __vmfAppendRaw(item: object): void {
        this.#items.push(item as T);
    }

    /** A change of a list with an opposite also changes its members, which anybody may observe. */
    #watched(): boolean {
        return this.opposite === undefined ? watched(this.owner) : watching();
    }

    #insert(index: number, item: T, frame: Frame | undefined): void {
        insertAt(this.#items, index, item);
        if (frame !== undefined) {
            inserted(frame, this.owner, this.property, this.#items, index, item);
        }
    }

    #removeAt(index: number, frame: Frame | undefined): void {
        const item = this.#items[index];
        removeAt(this.#items, index);
        if (frame !== undefined) {
            removed(frame, this.owner, this.property, this.#items, index, item);
        }
        if (this.opposite !== undefined) {
            unlink(item, this.opposite, this.owner, frame);
        }
    }

    /** True if the item's opposite already points to this owner. */
    #linked(item: T): boolean {
        const end = endOf(item, this.opposite!);
        return end instanceof ReferenceSlot ? end.value === this.owner : this.contains(item);
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
