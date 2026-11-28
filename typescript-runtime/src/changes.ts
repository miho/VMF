/**
 * The kind of a property change: a single value was set, or elements of a list or set, or entries of a map, were
 * added, removed or replaced.
 */
export type ChangeKind = "set" | "add" | "remove" | "replace";

/** A change of one property of one model object. */
export interface ModelChange {
    readonly source: object;
    readonly property: string;
    readonly kind: ChangeKind;
    /** The previous value of a "set" change. */
    readonly oldValue?: unknown;
    /** The new value of a "set" change. */
    readonly newValue?: unknown;
    /** The added elements of an "add" or "replace" change, or the removed elements of a "remove" change. */
    readonly values?: readonly unknown[];
    /** The elements a "replace" change replaced. */
    readonly replaced?: readonly unknown[];
    /** The positions of the elements in an ordered list. */
    readonly indices?: readonly number[];
    /** The keys of the entries of a map change, one for each value. */
    readonly keys?: readonly unknown[];
}

export type ModelChangeListener = (change: ModelChange) => void;

/** "local" observes one object; "recursive" also observes everything the object contains at the time of a change. */
export type ListenerScope = "local" | "recursive";

interface Effect {
    readonly change: ModelChange;
    readonly undo: () => void;
    readonly redo: () => void;
}

/** @internal The state changes of an edit in progress, in the order they were applied. */
export interface Frame {
    readonly origin: object;
    readonly effects: Effect[];
}

interface Registration {
    readonly listener: ModelChangeListener;
    readonly recursive: boolean;
}

/** @internal The listeners and the recording of one model object. Every {@link ModelChanges} of the object uses them. */
export interface Watch {
    listeners: readonly Registration[];
    recorder: ModelChanges | undefined;
    handle: ModelChanges | undefined;
}

/** @internal What the change machinery keeps on, and reads from, the containment link of a model object. */
export interface WatchedLink {
    /** The object that contains the model object. */
    readonly owner: object | undefined;
    __vmfWatch: Watch | undefined;
}

let active: Frame | undefined;
let replaying = false;
/** The number of listeners and of recordings. While both are zero, nobody can observe a change. */
let listening = 0;
let recording = 0;
let linkOf: (model: object) => WatchedLink | undefined = () => undefined;
/** Listeners and recordings of objects that have no containment link. Generated model objects have one. */
const unlinked = new WeakMap<object, Watch>();

/** @internal Lets the containment code tell the change machinery where a model object keeps its containment link. */
export function __vmfUseContainers(lookup: (model: object) => WatchedLink | undefined): void {
    linkOf = lookup;
}

/**
 * Runs one model edit. Every change the body makes becomes part of the edit: listeners are notified after the
 * body finished, recordings receive the edit as one undoable unit, and a body that throws leaves the model as it
 * was. Edits started inside the body join the outer edit.
 */
export function edit<T>(origin: object, body: () => T): T {
    if (active !== undefined) {
        return body();
    }
    __vmfRequireEditable();
    const frame: Frame = { origin, effects: [] };
    active = frame;
    let result: T;
    try {
        result = body();
    } catch (error) {
        active = undefined;
        for (let index = frame.effects.length - 1; index >= 0; index--) {
            frame.effects[index].undo();
        }
        throw error;
    }
    active = undefined;
    complete(frame);
    return result;
}

/**
 * @internal Opens the record of a change that a property or collection is about to apply, as an edit of its own
 * unless an edit is open. Without an open edit and without anybody who may observe the change (`watched`) there is
 * no record: the caller changes the state and registers nothing. That is why a property or collection checks
 * everything before it changes anything: a change without a record cannot be taken back.
 */
export function __vmfBegin(origin: object, watched: boolean): Frame | undefined {
    if (active !== undefined) {
        return active;
    }
    __vmfRequireEditable();
    return watched ? { origin, effects: [] } : undefined;
}

/** @internal Completes what {@link __vmfBegin} opened: an edit of its own is recorded and reported to listeners. */
export function __vmfEnd(frame: Frame | undefined): void {
    if (frame !== undefined && frame !== active) {
        complete(frame);
    }
}

/** @internal Registers a state change that was just applied, with the way back and the way forward again. */
export function __vmfEffect(frame: Frame, change: ModelChange, undo: () => void, redo: () => void): void {
    frame.effects.push({ change, undo, redo });
}

/** @internal Rejects a change outside of an edit while an edit is undone or redone. */
export function __vmfRequireEditable(): void {
    if (replaying && active === undefined) {
        throw new Error("A model cannot be edited while an edit is undone or redone.");
    }
}

/**
 * @internal True if somebody may observe a change of the object: an edit is open, or the object or one of its
 * containers has a listener or records.
 */
export function __vmfWatched(model: object): boolean {
    if (active !== undefined) {
        return true;
    }
    if (listening === 0 && recording === 0) {
        return false;
    }
    for (let current: object | undefined = model; current !== undefined;) {
        const link = linkOf(current);
        const watch = watchAt(current, link);
        if (watch !== undefined && (watch.listeners.length > 0 || watch.recorder !== undefined)) {
            return true;
        }
        current = link?.owner;
    }
    return false;
}

/** @internal True if somebody may observe a change of any object: an edit is open, or there are listeners or recordings. */
export function __vmfWatching(): boolean {
    return active !== undefined || listening > 0 || recording > 0;
}

/** Everything one edit changed. A recorded edit can be undone and redone. */
export class ModelEdit {
    readonly #effects: readonly Effect[];

    constructor(readonly origin: object, effects: readonly Effect[]) {
        this.#effects = effects;
    }

    get changes(): readonly ModelChange[] {
        return this.#effects.map(effect => effect.change);
    }

    /** Restores the state before the edit. Assumes the model has not changed since. */
    undo(): void {
        replay(this.#effects, true);
    }

    /** Applies the edit again after it was undone. */
    redo(): void {
        replay(this.#effects, false);
    }
}

/** Observation and recording of one model object. */
export class ModelChanges {
    readonly #history: ModelEdit[] = [];
    readonly #watch: Watch;

    constructor(readonly model: object) {
        this.#watch = watchOf(model);
    }

    /** Adds a listener and returns a function that removes it again. */
    addListener(listener: ModelChangeListener, scope: ListenerScope = "recursive"): () => void {
        const registration: Registration = { listener, recursive: scope === "recursive" };
        const watch = this.#watch;
        // A new array for every change: a notification in progress keeps calling the listeners it started with.
        watch.listeners = [...watch.listeners, registration];
        listening++;
        let removed = false;
        return () => {
            if (removed) {
                return;
            }
            removed = true;
            listening--;
            watch.listeners = watch.listeners.filter(candidate => candidate !== registration);
        };
    }

    /** Starts recording the edits of this object and of everything it contains. */
    start(): void {
        if (this.#watch.recorder === undefined) {
            recording++;
        }
        this.#watch.recorder = this;
    }

    stop(): void {
        if (this.#watch.recorder !== undefined) {
            recording--;
            this.#watch.recorder = undefined;
        }
    }

    get isRecording(): boolean {
        return this.#watch.recorder === this;
    }

    /** The recorded edits, oldest first. */
    get history(): readonly ModelEdit[] {
        return [...this.#history];
    }

    clear(): void {
        this.#history.length = 0;
    }

    /** @internal Adds the edit that was just completed, once. */
    __vmfRecord(edit: ModelEdit): void {
        if (this.#history[this.#history.length - 1] !== edit) {
            this.#history.push(edit);
        }
    }
}

/** The observation and recording handle of a model object. */
export function changesOf(model: object): ModelChanges {
    const watch = watchOf(model);
    return watch.handle ??= new ModelChanges(model);
}

/** The listeners and the recording of an object, if it has any: on its containment link, or without one in the table. */
function watchAt(model: object, link: WatchedLink | undefined): Watch | undefined {
    return link === undefined ? unlinked.get(model) : link.__vmfWatch;
}

function watchOf(model: object): Watch {
    const link = linkOf(model);
    let watch = watchAt(model, link);
    if (watch === undefined) {
        watch = { listeners: [], recorder: undefined, handle: undefined };
        if (link === undefined) {
            unlinked.set(model, watch);
        } else {
            link.__vmfWatch = watch;
        }
    }
    return watch;
}

function complete(frame: Frame): void {
    if (frame.effects.length > 0) {
        if (recording > 0) {
            record(frame.origin, frame.effects);
        }
        if (listening > 0) {
            notify(frame.effects, false);
        }
    }
}

/**
 * An edit belongs to the recordings of the object it was made on and of the objects that contain it. If there is
 * none, it belongs to the recordings of the objects it changed and of the objects that contain those.
 */
function record(origin: object, effects: readonly Effect[]): void {
    const completed = new ModelEdit(origin, effects);
    if (!recordFor(origin, completed)) {
        for (const effect of effects) {
            recordFor(effect.change.source, completed);
        }
    }
}

/** Adds the edit to the recordings of the object and of the objects that contain it. Returns true if there is one. */
function recordFor(model: object, completed: ModelEdit): boolean {
    let recorded = false;
    for (let current: object | undefined = model; current !== undefined;) {
        const link = linkOf(current);
        const recorder = watchAt(current, link)?.recorder;
        if (recorder !== undefined) {
            recorder.__vmfRecord(completed);
            recorded = true;
        }
        current = link?.owner;
    }
    return recorded;
}

/**
 * Reports the changes in their order, each to the listeners of the changed object and then to the recursive
 * listeners of the objects that contain it, nearest first. `undone` reports the reverse of the changes, last first.
 */
function notify(effects: readonly Effect[], undone: boolean): void {
    let failure: unknown;
    let failed = false;
    const count = effects.length;
    for (let index = 0; index < count; index++) {
        const change = undone ? invert(effects[count - 1 - index].change) : effects[index].change;
        let own = true;
        for (let current: object | undefined = change.source; current !== undefined;) {
            const link = linkOf(current);
            const watch = watchAt(current, link);
            if (watch !== undefined) {
                const registrations = watch.listeners;
                for (let position = 0; position < registrations.length; position++) {
                    const registration = registrations[position];
                    if (own || registration.recursive) {
                        try {
                            registration.listener(change);
                        } catch (error) {
                            if (!failed) {
                                failed = true;
                                failure = error;
                            }
                        }
                    }
                }
            }
            own = false;
            // Read after the listeners ran: a listener may have moved the object.
            current = link?.owner;
        }
    }
    if (failed) {
        throw failure;
    }
}

function replay(effects: readonly Effect[], backwards: boolean): void {
    if (active !== undefined || replaying) {
        throw new Error("An edit cannot be undone or redone inside another edit.");
    }
    replaying = true;
    try {
        if (backwards) {
            for (let index = effects.length - 1; index >= 0; index--) {
                effects[index].undo();
            }
        } else {
            for (let index = 0; index < effects.length; index++) {
                effects[index].redo();
            }
        }
    } finally {
        replaying = false;
    }
    if (listening > 0) {
        notify(effects, backwards);
    }
}

function invert(change: ModelChange): ModelChange {
    switch (change.kind) {
        case "set":
            return { ...change, oldValue: change.newValue, newValue: change.oldValue };
        case "add":
            return { ...change, kind: "remove" };
        case "remove":
            return { ...change, kind: "add" };
        case "replace":
            return { ...change, values: change.replaced, replaced: change.values };
    }
}
