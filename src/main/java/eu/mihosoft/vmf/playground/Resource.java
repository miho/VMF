package eu.mihosoft.vmf.playground;

import java.io.IOException;
import java.io.PrintWriter;

public interface Resource extends AutoCloseable {

	PrintWriter open() throws IOException;

	void close() throws IOException;

}
