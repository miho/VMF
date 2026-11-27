package eu.mihosoft.vmf.conformance.collectionredeclarations.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;

import java.util.List;
import java.util.Set;

interface CollectionCatalog {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) CollectionBasis[] getExamples();
    FrozenBasis getFrozen();
    @Contains IntegralSeries getNumeric();
}

interface CollectionBasis {
    @Annotation(key = "vmf:jackson:rename", value = "base_numbers")
    @Multiplicity(lower = 1, upper = 3) List<Integer> getNumbers();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 2) List<String> getLabels();
    @Multiplicity(upper = 2) Set<Long> getCodes();
    @Multiplicity(upper = 2) VMap<String, Integer> getCounters();

    @Contains(opposite = "basis") @Keyed(by = "name")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 2)
    CollectionItem[] getOwned();

    @Refers(opposite = "selectedBy") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    CollectionItem[] getFeatured();

    CollectionItem[] getTrail();
    Set<CollectionItem> getFavorites();
}

interface CollectionVariant extends CollectionBasis {
    @Override @Annotation(key = "vmf:jackson:rename", value = "numbers_v2") List<Integer> getNumbers();
    @Override List<String> getLabels();
    @Override Set<Long> getCodes();
    @Override VMap<String, Integer> getCounters();
    @Override @Annotation(key = "vmf:jackson:rename", value = "owned_v2") CollectionItem[] getOwned();
    @Override CollectionItem[] getFeatured();
    @Override CollectionItem[] getTrail();
    @Override Set<CollectionItem> getFavorites();
}

interface CollectionItem {
    String getName();
    @Container(opposite = "owned") CollectionBasis getBasis();
    @Refers(opposite = "featured") CollectionBasis getSelectedBy();
}

@Immutable
interface FrozenToken { String getLabel(); }

@Immutable
interface FrozenBasis {
    @Annotation(key = "vmf:jackson:rename", value = "previous_numbers")
    @Multiplicity(lower = 1, upper = 3) List<Integer> getNumbers();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 2) List<String> getLabels();
    @Multiplicity(upper = 2) Set<Long> getCodes();
    @Multiplicity(upper = 2) VMap<String, Integer> getCounters();
    FrozenToken[] getTrail();
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) FrozenToken[] getFeatured();
    Set<FrozenToken> getFavorites();
}

@Immutable
interface FrozenVariant extends FrozenBasis {
    @Override List<Integer> getNumbers();
    @Override List<String> getLabels();
    @Override Set<Long> getCodes();
    @Override VMap<String, Integer> getCounters();
    @Override FrozenToken[] getTrail();
    @Override FrozenToken[] getFeatured();
    @Override Set<FrozenToken> getFavorites();
}

@InterfaceOnly
interface NumericSeries<T extends Number> {
    @Multiplicity(upper = 3) List<T> getReadings();
    @Multiplicity(upper = 2) VMap<String, T> getMarks();
}

interface IntegralSeries extends NumericSeries<Long> {
    @Override List<Long> getReadings();
    @Override VMap<String, Long> getMarks();
}
