package eu.mihosoft.vmf.runtime.core;

import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Just like {@link Objects}, this class consists of static utility methods for operating on VMF objects
 * (instances of {@link VObject}), or checking certain conditions before operation.
 */
public final class VObjects {

    private VObjects() {
        throw new AssertionError("Don't instantiate me!");
    }

    /**
     * Returns {@code true} if the arguments are equal to each other; {@code false} otherwise. If
     * the specified objects are model instances then the VMF {@code equals} method is used. If the specified
     * objects are collections which contain model instances then the VMF {@code equals} method is used element-wise.
     * @param o1 first object to compare
     * @param o2 second object to compare
     * @return {@code true} if the arguments are equal to each other; {@code false} otherwise.
     */
    @SuppressWarnings("deprecation")
    public static boolean equals(Object o1, Object o2) {
        if(o1 instanceof VObject && o2 instanceof VObject) {
            return ((VObjectInternal)o1)._vmf_equals(o2);
        } else if(o1 instanceof Collection && o2 instanceof Collection) {
            Collection l1 = (Collection) o1;
            Collection l2 = (Collection) o2;

            if(l1.size()!=l2.size()) {
                return false;
            }

            int n = l1.size();

            var l1It = l1.iterator();
            var l2It = l2.iterator();

            // compare element-wise
            for(int i = 0; i < n; i++) {

                Object e1 = l1It.next();
                Object e2 = l2It.next();

                if(!equals(e1,e2)) return false;
            }
        }

        return Objects.equals(o1, o2);
    }
}
