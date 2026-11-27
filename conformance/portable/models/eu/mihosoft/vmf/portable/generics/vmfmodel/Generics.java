package eu.mihosoft.vmf.portable.generics.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;

// Generic model interfaces, from test-suite vmftest/genericseries (2026) and vmftest/keyed (2026): scalar type
// parameters that subtypes bind to numbers, strings or immutable values, parameters passed on in another order,
// a model-bounded parameter, and one base reached through two generic supertypes.

@InterfaceOnly
interface Series<T> {
    T getLatest();

    T[] getSamples();
}

@InterfaceOnly
interface NumericSeries<N extends Number> extends Series<N> {
}

interface Temperatures extends NumericSeries<Double> {
}

interface Counters extends NumericSeries<Long> {
}

interface Labels extends Series<String> {
}

@InterfaceOnly
interface Pair<L, R> {
    L getLeft();

    R getRight();
}

@InterfaceOnly
interface Swapped<L, R> extends Pair<R, L> {
}

interface NamedCount extends Swapped<Integer, String> {
}

@Immutable
interface Code {
    String getName();
}

@InterfaceOnly
interface Slot<T> {
    T getValue();

    T[] getHistory();
}

interface CodeSlot extends Slot<Code> {
}

interface Entry {
    String getKey();
}

@InterfaceOnly
interface Holder<E extends Entry> {
    @Contains
    E[] getEntries();

    E getFavorite();
}

interface Ledger extends Holder<Entry> {
}

@InterfaceOnly
interface LeftBranch<X extends Entry> extends Holder<X> {
}

@InterfaceOnly
interface RightBranch<Y extends Entry> extends Holder<Y> {
}

interface Journal extends LeftBranch<Entry>, RightBranch<Entry> {
}

// A generic supertype that a class implements besides its base class declares its scalar slots in that class.
interface Header {
    String getId();
}

interface ReviewedSlot extends Header, Slot<Code> {
}

// The container narrows its end to a subtype, from test-suite vmftest/genericseries (OwnerOf, OwnedTarget).
@InterfaceOnly
interface OwnerOf<T extends OwnedTarget> {
    @Contains(opposite = "owner")
    T getPrimary();
}

interface Owner extends OwnerOf<Owned> {
    @Override
    Owned getPrimary();
}

@InterfaceOnly
interface OwnedTarget {
    @Container(opposite = "primary")
    Owner getOwner();
}

interface Owned extends OwnedTarget {
    String getName();
}
