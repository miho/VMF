package eu.mihosoft.vmf.core;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Refers {
   /**
     * Sets the opposite property of this cross-reference relationship, e.g., <b>"prop"</b> (short form) or <b>"ClassName.prop"</b> (full property).
     */
    String opposite();
}