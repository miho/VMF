package eu.mihosoft.vmf.runtime.core.internal;

/** Resolves binary or canonical Java names from the model's declared metadata. */
public final class DeclaredClassNames {
    private DeclaredClassNames() { }

    public static Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException {
        try {
            return loadExact(name, loader);
        } catch (ClassNotFoundException missing) {
            String candidate = name;
            for (int separator = candidate.lastIndexOf('.'); separator >= 0;
                 separator = candidate.lastIndexOf('.', separator - 1)) {
                candidate = candidate.substring(0, separator) + '$' + candidate.substring(separator + 1);
                try {
                    Class<?> nested = loadExact(candidate, loader);
                    if (name.equals(nested.getCanonicalName())) return nested;
                } catch (ClassNotFoundException ignored) {
                    // A preceding component may also name an enclosing class.
                }
            }
            throw missing;
        }
    }

    private static Class<?> loadExact(String name, ClassLoader loader) throws ClassNotFoundException {
        return loader == null ? Class.forName(name, false, null) : loader.loadClass(name);
    }
}
