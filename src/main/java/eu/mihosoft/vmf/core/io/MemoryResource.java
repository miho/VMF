package eu.mihosoft.vmf.core.io;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class MemoryResource implements Resource {

    CharArrayWriter mem = new CharArrayWriter();

    @Override
    public PrintWriter open() throws IOException {
        return new PrintWriter(mem);
    }

    @Override
    public void close() throws IOException {
        mem.close();
    }

    public String asString() {
        return mem.toString();
    }
}
