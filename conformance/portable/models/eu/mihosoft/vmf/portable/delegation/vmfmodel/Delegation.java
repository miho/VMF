package eu.mihosoft.vmf.portable.delegation.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

// Behavior delegation (contract module "behavior"). Behaviors: Java eu.mihosoft.vmf.portable.delegation (next to
// this package), C# conformance/portable/csharp/Hosts, TypeScript conformance/portable/typescript/hosts.

/** The type-level binding also supplies the construction hook, onCounterInstantiated. */
@DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
interface Counter {
    @DefaultValue("5")
    int getStart();

    String getNote();

    /** start + amount */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
    int add(int amount);

    /** prefix + ":" + note */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
    String label(String prefix);

    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
    int sum(int... values);

    /** The counter with the larger start, this one if equal. */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
    Counter larger(Counter other);

    /** Sets start to 0 through the caller. */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
    void reset();

    /** How often add was called on this object's behavior. */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
    int calls();

    /** The start value the construction hook saw. */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
    int startSeenByHook();

    /** How many construction hooks ran after the one of this object. */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.CounterBehavior")
    int hooksSince();
}

/** An interface-only type that binds its method for all subtypes. */
@InterfaceOnly
interface Shape {
    String getName();

    /** "shape " + name */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.ShapeBehavior")
    String describe();
}

/** Uses the binding of Shape. */
interface Square extends Shape {
    double getSide();
}

/** Binds describe again. */
interface Tile extends Square {
    /** "tile " + name */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.TileBehavior")
    String describe();
}

/** Its construction hook appends "first" to origin. */
@DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.FirstHook")
interface FirstHooked {
    String getOrigin();

    /** "first role" */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.FirstHook")
    String firstRole();
}

/** Its construction hook appends "second" to origin. */
@DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.SecondHook")
interface SecondHooked {
    String getOrigin();

    /** "second role" */
    @DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.SecondHook")
    String secondRole();
}

/** Binds no hook: only the one of FirstHooked, its first supertype that binds one, runs (B3). */
interface BothHooked extends FirstHooked, SecondHooked {
}

/** Binds a hook of its own, which runs instead of the one of FirstHooked (B3). */
@DelegateTo(className = "eu.mihosoft.vmf.portable.delegation.OwnHook")
interface OwnHooked extends FirstHooked {
}
