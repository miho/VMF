package eu.mihosoft.vmf.core.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class IOUtils {
    private IOUtils() {
        throw new AssertionError("Don't instantiate me!");
    }

    /**
     * Reads the specified resource into a string.
     * 
     * @param resource resource to read from
     * @return the string read from the specified resource
     * @throws IOException if an I/O related error occurs
     */
    public static String resourceToString(Resource resource) throws IOException {

        ByteArrayOutputStream result = new ByteArrayOutputStream();

        try { 
            InputStream inputStream = resource.openForReading();
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
        } finally {
            resource.close();
        }

        return result.toString(StandardCharsets.UTF_8.name());
    }
}