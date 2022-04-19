package eu.mihosoft.vmf.core;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.ElementType;

/**
 * Indicates that an automatically generated
 * {@code java.lang.Object#equals(Object)} and
 * {@code java.lang.Object#hashCode()} should be used instead of the default
 * implementation. If the annotation is not used, VMFs {@code equals()} and 
 * {@code hashCode()} methods can be accessed via {@code obj.content().equals()}
 * and  {@code obj.content().hashCode()}.
 * 
 * <p>
 * Created by miho on 14.10.19.
 * </p>
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 * 
 * @see {@link IgnoreEquals}
 * @see <a
 *      href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-10/README.md#ignoring-properties-for-equals">Tutorial
 *      on Equals & HashCode</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface VMFEquals {

    /**
     * Defines the {@link Object#equals(Object)} and  {@link Object#hashCode()}
     * implementation to use.
     */
    enum EqualsType {
        CONTAINMENT_AND_EXTERNAL,
        ALL,
        INSTANCE
    }

    /**
     * Defines the {@link Object#equals(Object)} and  {@link Object#hashCode()}
     * implementation to use. The default is {@link EqualsType#INSTANCE}.
     */
    EqualsType value() default EqualsType.CONTAINMENT_AND_EXTERNAL;
}