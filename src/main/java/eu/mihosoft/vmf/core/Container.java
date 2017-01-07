package eu.mihosoft.vmf.core;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by miho on 02.01.2017.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Container {
    String opposite();
}
