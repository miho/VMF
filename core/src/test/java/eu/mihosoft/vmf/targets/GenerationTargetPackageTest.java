package eu.mihosoft.vmf.targets;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/** The build plugins generate other languages from the compiled model interfaces of a package. */
public class GenerationTargetPackageTest {
    private static final String PACKAGE = "eu.mihosoft.vmf.targets.vmfmodel";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void generatesCSharpFromAPackage() throws Exception {
        File output = folder.newFolder("csharp");
        VMF.generate(GenerationTarget.CSHARP, output, getClass().getClassLoader(), PACKAGE);
        String source = Files.readString(new File(output, "Eu/Mihosoft/Vmf/Targets/Model.g.cs").toPath(),
                StandardCharsets.UTF_8);
        assertTrue(source, source.contains("public sealed class Library"));
        assertTrue(source, source.contains("public sealed class Book"));
    }

    @Test
    public void generatesTypeScriptFromAPackage() throws Exception {
        File output = folder.newFolder("typescript");
        VMF.generate(GenerationTarget.TYPESCRIPT, output, getClass().getClassLoader(), PACKAGE);
        String source = Files.readString(new File(output, "eu/mihosoft/vmf/targets/model.g.ts").toPath(),
                StandardCharsets.UTF_8);
        assertTrue(source, source.contains("export class Library"));
        assertTrue(source, source.contains("export class Book"));
    }
}
