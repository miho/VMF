package eu.mihosoft.vmf.conformance.behavior;

public final class TraitDefaultProviders {
    static int calls;
    static boolean returnsNull;

    public static String label() {
        int call = ++calls;
        return returnsNull ? null : "shared-" + call;
    }

    private TraitDefaultProviders() { }
}
