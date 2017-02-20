package eu.mihosoft.vmf.core;

import java.io.IOException;
import java.io.PrintWriter;

public interface Resource extends AutoCloseable {

    //
    // thanks to Sam for designing this interface
    //
    PrintWriter open() throws IOException;

    void close() throws IOException;

}
