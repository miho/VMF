package eu.mihosoft.vmf.core;

import java.lang.annotation.*;

/**
 * Created by miho on 20.03.17.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SyncWith {
    String opposite();
}
