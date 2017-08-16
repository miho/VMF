package eu.mihosoft.vmf.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Created by miho on 02.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GetterOnly {

}
