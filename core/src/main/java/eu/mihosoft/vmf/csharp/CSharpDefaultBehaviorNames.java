package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** Keeps single-provider names stable and distinguishes multiple providers by source binding. */
final class CSharpDefaultBehaviorNames {
    private CSharpDefaultBehaviorNames() {
        throw new AssertionError();
    }

    static String simpleName(String binding) {
        int separator = Math.max(binding.lastIndexOf('.'), binding.lastIndexOf('$'));
        return binding.substring(separator + 1);
    }

    static Set<String> declaredBindings(Type owner) {
        var bindings = new LinkedHashSet<String>();
        owner.getDeclaredMethods().stream().map(method -> method.getJavaBinding())
                .filter(binding -> !binding.isBlank()).forEach(bindings::add);
        owner.getConstructionHook().filter(hook -> hook.getDeclaringType().equals(owner.getName()))
                .map(hook -> hook.getJavaBinding()).filter(binding -> !binding.isBlank())
                .ifPresent(bindings::add);
        return bindings;
    }

    static Map<String, String> disambiguate(Collection<String> bindings, Function<String, String> preferredName) {
        var names = new TreeMap<String, String>();
        var used = new HashSet<String>();
        var ambiguous = new HashSet<String>();
        for (String binding : bindings) {
            String preferred = preferredName.apply(binding);
            names.put(binding, preferred);
            if (!used.add(preferred)) ambiguous.add(preferred);
        }
        names.replaceAll((binding, preferred) -> {
            if (!ambiguous.contains(preferred)) return preferred;
            String qualified = preferred + "__" + encodeBinding(binding);
            while (!used.add(qualified)) qualified += "_";
            return qualified;
        });
        return Map.copyOf(names);
    }

    private static String encodeBinding(String binding) {
        var encoded = new StringBuilder();
        for (int index = 0; index < binding.length(); index++) {
            char character = binding.charAt(index);
            if (character < 128 && Character.isLetterOrDigit(character)) {
                encoded.append(character);
            } else {
                switch (character) {
                    case '.': encoded.append("_p"); break;
                    case '$': encoded.append("_n"); break;
                    case '_': encoded.append("__"); break;
                    default: encoded.append("_u").append(Integer.toHexString(character)).append('_');
                }
            }
        }
        return encoded.toString();
    }
}
