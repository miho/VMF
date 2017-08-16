package eu.mihosoft.vmf.core;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by miho on 07.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface VMFModel {
    String destinationPackage();
}
