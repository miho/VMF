package eu.mihosoft.vmf.core;

import java.lang.annotation.*;

/**
 * Created by miho on 02.01.2017.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Contains {
    String opposite();
}
