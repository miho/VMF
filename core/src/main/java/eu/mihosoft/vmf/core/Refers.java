package eu.mihosoft.vmf.core;

import java.lang.annotation.*;

/**
 * Used to define a cross reference (bidirectional linking relationship). 
 * <h3>Example Model:</h3>
 * <pre><code>
 * package mypkg.vmfmodel;
 * 
 * import eu.mihosoft.vmf.core.*;
 *
 * interface Book {
 *    String getTitle();
 *
 *    @Refers(opposite="books")
 *    Writer[] getAuthors();
 * }
 *
 * interface Writer {
 *    String getName();
 *
 *    @Refers(opposite="authors")
 *    Book[] getBooks();
 * }
 * </code></pre>
 * 
 * <p>Created by miho on 12.03.2019.</p>
 * 
 * @see Contains
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-03b/README.md">Tutorial on Cross References</a>
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Refers {
   /**
     * Sets the opposite property of this cross reference relationship, e.g., <b>"prop"</b> (short form) or <b>"ClassName.prop"</b> (full property).
     */
    String opposite();
}