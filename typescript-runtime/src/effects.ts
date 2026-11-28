import { __vmfEffect as effect, type Frame } from "./changes.js";

// What the lists and sets of scalars and of model objects register with an edit after they changed, and the array
// edits they share. These functions are separate from the collection methods that change the state: a method that
// creates the undo and redo closures pays for them on every call, also when nobody observes the change and there
// is no edit to register them with.

/**
 * @internal Inserts the value at the index, or at the end if the index is beyond it: `items.splice(index, 0, value)`
 * without the array that `splice` creates for the elements it removed, on every call.
 */
export function insertAt<T>(items: T[], index: number, value: T): void {
    let position = items.length;
    items.push(value);
    if (index < position) {
        for (; position > index; position--) {
            items[position] = items[position - 1];
        }
        items[index] = value;
    }
}

/** @internal Removes the element at the index, if there is one: `items.splice(index, 1)` without its array. */
export function removeAt<T>(items: T[], index: number): void {
    const last = items.length - 1;
    if (index <= last) {
        for (let position = index; position < last; position++) {
            items[position] = items[position + 1];
        }
        items.pop();
    }
}

/** @internal Registers an element that was just inserted into the items of a list. */
export function inserted<T>(frame: Frame, owner: object, property: string, items: T[], index: number, value: T): void {
    effect(frame, { source: owner, property, kind: "add", values: [value], indices: [index] },
        () => { removeAt(items, index); }, () => { insertAt(items, index, value); });
}

/** @internal Registers an element that was just removed from the items of a list. */
export function removed<T>(frame: Frame, owner: object, property: string, items: T[], index: number, value: T): void {
    effect(frame, { source: owner, property, kind: "remove", values: [value], indices: [index] },
        () => { insertAt(items, index, value); }, () => { removeAt(items, index); });
}

/** @internal Registers an element of a list that was just replaced. */
export function replaced<T>(frame: Frame, owner: object, property: string, items: T[], index: number, previous: T,
                            value: T): void {
    effect(frame, { source: owner, property, kind: "replace", values: [value], replaced: [previous], indices: [index] },
        () => { items[index] = previous; }, () => { items[index] = value; });
}

/** @internal Registers a member that was just added to the items of a set. */
export function memberAdded<T>(frame: Frame, owner: object, property: string, items: Set<T>, value: T): void {
    effect(frame, { source: owner, property, kind: "add", values: [value] },
        () => { items.delete(value); }, () => { items.add(value); });
}

/** @internal Registers a member that was just removed from the items of a set. */
export function memberRemoved<T>(frame: Frame, owner: object, property: string, items: Set<T>, value: T): void {
    effect(frame, { source: owner, property, kind: "remove", values: [value] },
        () => { items.add(value); }, () => { items.delete(value); });
}
