// Host code of the portable model "delegation" (contract module "behavior"): the default behaviors that
// runner.mjs installs and the replacement behaviors that scenarios name.
import type {
    Counter, CounterBehavior, ModelBindings,
} from "../build/generated/eu/mihosoft/vmf/portable/delegation/model.g.js";

let hooks = 0;

/** The default behavior of Counter; each object has its own. */
class DefaultCounter implements CounterBehavior {
    #calls = 0;
    #seenStart = -1;
    #sequence = 0;

    /** Runs before the declared defaults: it sees start 0, and the default replaces the 99 it sets. */
    onCounterInstantiated(caller: Counter): void {
        this.#seenStart = caller.start;
        caller.note = "hooked";
        caller.start = 99;
        this.#sequence = ++hooks;
    }

    add(caller: Counter, amount: number): number {
        this.#calls++;
        return caller.start + amount;
    }

    label(caller: Counter, prefix: string | null): string | null {
        return `${prefix}:${caller.note}`;
    }

    sum(_caller: Counter, values: readonly number[] | null): number {
        return (values ?? []).reduce((total, value) => total + value, 0);
    }

    larger(caller: Counter, other: Counter | null): Counter | null {
        return other !== null && other.start > caller.start ? other : caller;
    }

    reset(caller: Counter): void {
        caller.start = 0;
    }

    calls(): number {
        return this.#calls;
    }

    startSeenByHook(): number {
        return this.#seenStart;
    }

    hooksSince(): number {
        return hooks - this.#sequence;
    }
}

/** A replacement behavior for one Counter. */
class AlternativeCounter implements CounterBehavior {
    onCounterInstantiated(): void {
        throw new Error("A replacement behavior never receives the construction hook.");
    }

    add(_caller: Counter, amount: number): number {
        return 100 * amount;
    }

    label(caller: Counter, prefix: string | null): string | null {
        return `${prefix}@${caller.start}`;
    }

    sum(_caller: Counter, values: readonly number[] | null): number {
        return values?.length ?? 0;
    }

    larger(caller: Counter): Counter | null {
        return caller;
    }

    reset(caller: Counter): void {
        caller.start = -1;
    }

    calls(): number {
        return -1;
    }

    startSeenByHook(): number {
        return -1;
    }

    hooksSince(): number {
        return -1;
    }
}

/** Appends a construction hook's word to the origin, so that a second hook would show. */
function appendOrigin(origin: string | null, word: string): string {
    return origin === null ? word : `${origin},${word}`;
}

export const bindings: ModelBindings = {
    behaviors: {
        Counter: () => new DefaultCounter(),
        Shape: () => ({ describe: caller => `shape ${caller.name}` }),
        Tile: () => ({ describe: caller => `tile ${caller.name}` }),
        FirstHooked: () => ({
            onFirstHookedInstantiated: caller => { caller.origin = appendOrigin(caller.origin, "first"); },
            firstRole: () => "first role",
        }),
        SecondHooked: () => ({
            onSecondHookedInstantiated: caller => { caller.origin = appendOrigin(caller.origin, "second"); },
            secondRole: () => "second role",
        }),
        OwnHooked: () => ({
            onOwnHookedInstantiated: caller => { caller.origin = appendOrigin(caller.origin, "own"); },
        }),
    },
};

/** Replacement behaviors by the name that scenarios use in replaceBehavior steps. */
export const replacements: Readonly<Record<string, () => object>> = {
    AlternativeCounter: () => new AlternativeCounter(),
};
