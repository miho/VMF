package eu.mihosoft.vmf.core;

import java.io.IOException;
import java.io.PrintWriter;


/**
 * @author Sam
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public interface Resource extends AutoCloseable {

    //
    // thanks to Sam for designing this interface
    //
    PrintWriter open() throws IOException;

    @Override
    void close() throws IOException;

}
