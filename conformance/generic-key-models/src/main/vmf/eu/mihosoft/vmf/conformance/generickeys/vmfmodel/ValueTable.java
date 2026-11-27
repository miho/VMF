package eu.mihosoft.vmf.conformance.generickeys.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

@InterfaceOnly
interface KeyIndex<K> {
    VMap<K, String> getEntries();
}

@InterfaceOnly
interface ValueTable<K> extends KeyIndex<K> {
    K getSelected();
    VMap<K, K> getPaired();
    @Multiplicity(upper = 2) List<K> getChecked();
    Set<K> getKnown();
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 3) K[] getPriority();
    List<K> getHistory();
}

@InterfaceOnly
interface NumberedTable<K extends Number> extends ValueTable<K> { }

interface IntegerIndex extends KeyIndex<Integer> { }

interface IntegerTable extends ValueTable<Integer> {
    @Override VMap<Integer, Integer> getPaired();
    @Override @Multiplicity(upper = 2) List<Integer> getChecked();
}

interface FloatTable extends NumberedTable<Double> { }

interface StringTable extends ValueTable<String> { }

@InterfaceOnly
interface Prices<K, V> {
    @Multiplicity(upper = 2) VMap<K, V> getPrices();
}

interface PriceCatalog extends Prices<Integer, Long> { }
