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
    readonly change: ModelChange | undefined;
    readonly undo: () => void;
    readonly redo: () => void;
}

interface Frame {
    readonly origin: object;
    readonly effects: Effect[];
}

interface Registration {
    readonly listener: ModelChangeListener;
    readonly recursive: boolean;
}

let active: Frame | undefined;
let replaying = false;
let registrationCount = 0;
let ownerOf: (model: object) => object | undefined = () => undefined;
const listeners = new WeakMap<object, Registration[]>();
const recordings = new Map<object, ModelChanges>();
const handles = new WeakMap<object, ModelChanges>();

/** @internal Lets the containment code tell the change machinery who contains an object. */
export function __vmfUseContainers(lookup: (model: object) => object | undefined): void {
    ownerOf = lookup;
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
    if (replaying) {
        throw new Error("A model cannot be edited while an edit is undone or redone.");
    }
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
    if (frame.effects.length > 0) {
        const completed = new ModelEdit(origin, frame.effects);
        record(completed);
        notify(completed.changes);
    }
    return result;
}

/** @internal Registers a state change that was just applied by the active edit. */
export function __vmfEffect(change: ModelChange | undefined, undo: () => void, redo: () => void): void {
    if (active !== undefined) {
        active.effects.push({ change, undo, redo });
    }
}

/** Everything one edit changed. A recorded edit can be undone and redone. */
export class ModelEdit {
    readonly #effects: readonly Effect[];

    constructor(readonly origin: object, effects: readonly Effect[]) {
        this.#effects = effects;
    }

    get changes(): readonly ModelChange[] {
        return this.#effects.flatMap(effect => effect.change === undefined ? [] : [effect.change]);
    }

    /** Restores the state before the edit. Assumes the model has not changed since. */
    undo(): void {
        replay([...this.#effects].reverse().map(effect => ({
            apply: effect.undo, change: effect.change === undefined ? undefined : invert(effect.change),
        })));
    }

    /** Applies the edit again after it was undone. */
    redo(): void {
        replay(this.#effects.map(effect => ({ apply: effect.redo, change: effect.change })));
    }
}

/** Observation and recording of one model object. */
export class ModelChanges {
    readonly #history: ModelEdit[] = [];

    constructor(readonly model: object) {
    }

    /** Adds a listener and returns a function that removes it again. */
    addListener(listener: ModelChangeListener, scope: ListenerScope = "recursive"): () => void {
        const registration: Registration = { listener, recursive: scope === "recursive" };
        const registrations = listeners.get(this.model) ?? [];
        listeners.set(this.model, [...registrations, registration]);
        registrationCount++;
        let removed = false;
        return () => {
            if (removed) {
                return;
            }
            removed = true;
            registrationCount--;
            listeners.set(this.model, (listeners.get(this.model) ?? []).filter(candidate => candidate !== registration));
        };
    }

    /** Starts recording the edits of this object and of everything it contains. */
    start(): void {
        recordings.set(this.model, this);
    }

    stop(): void {
        recordings.delete(this.model);
    }

    get isRecording(): boolean {
        return recordings.get(this.model) === this;
    }

    /** The recorded edits, oldest first. */
    get history(): readonly ModelEdit[] {
        return [...this.#history];
    }

    clear(): void {
        this.#history.length = 0;
    }

    /** @internal */
    __vmfRecord(edit: ModelEdit): void {
        this.#history.push(edit);
    }
}

/** The observation and recording handle of a model object. */
export function changesOf(model: object): ModelChanges {
    let handle = handles.get(model);
    if (handle === undefined) {
        handle = new ModelChanges(model);
        handles.set(model, handle);
    }
    return handle;
}

function contains(root: object, model: object): boolean {
    for (let current: object | undefined = model; current !== undefined; current = ownerOf(current)) {
        if (current === root) {
            return true;
        }
    }
    return false;
}

/**
 * An edit belongs to the recordings that contain the object it was made on. If none does, it belongs to the
 * recordings that contain any object it changed.
 */
function record(completed: ModelEdit): void {
    if (recordings.size === 0) {
        return;
    }
    let owners = [...recordings].filter(([root]) => contains(root, completed.origin));
    if (owners.length === 0) {
        const sources = completed.changes.map(change => change.source);
        owners = [...recordings].filter(([root]) => sources.some(source => contains(root, source)));
    }
    for (const [, changes] of owners) {
        changes.__vmfRecord(completed);
    }
}

function notify(changes: readonly ModelChange[]): void {
    if (registrationCount === 0) {
        return;
    }
    let failure: unknown;
    let failed = false;
    for (const change of changes) {
        let depth = 0;
        for (let current: object | undefined = change.source; current !== undefined; current = ownerOf(current)) {
            for (const registration of listeners.get(current) ?? []) {
                if (depth === 0 || registration.recursive) {
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
            depth++;
        }
    }
    if (failed) {
        throw failure;
    }
}

function replay(steps: readonly { apply: () => void; change: ModelChange | undefined }[]): void {
    if (active !== undefined || replaying) {
        throw new Error("An edit cannot be undone or redone inside another edit.");
    }
    replaying = true;
    try {
        for (const step of steps) {
            step.apply();
        }
    } finally {
        replaying = false;
    }
    notify(steps.flatMap(step => step.change === undefined ? [] : [step.change]));
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
