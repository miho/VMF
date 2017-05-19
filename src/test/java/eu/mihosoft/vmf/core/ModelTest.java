package eu.mihosoft.vmf.core;

import org.junit.BeforeClass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by miho on 19.05.17.
 */
public class ModelTest {

    private static Path tmpDir;

    @BeforeClass
    public static void initDirs() throws IOException {
        tmpDir = Files.createTempDirectory("vmf-testing-");
    }



}
