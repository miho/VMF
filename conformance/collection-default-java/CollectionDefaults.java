package eu.mihosoft.vmf.conformance.collectiondefaults;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CollectionDefaults {
    static final List<Integer> numbers = new ArrayList<>(List.of(3, 5));
    static final List<String> labels = new ArrayList<>(List.of("draft", "published"));
    static final Map<String, Integer> options = new LinkedHashMap<>();
    static int numberCalls;

    static { options.put("present", null); options.put("known", 11); }

    private CollectionDefaults() { }

    public static List<Integer> numbers() { numberCalls++; return numbers; }
    public static List<String> labels() { return labels; }
    public static Set<Long> codes() { return new LinkedHashSet<>(List.of(19L, 23L)); }
    public static Map<String, Integer> options() { return options; }
}
