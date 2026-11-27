package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/** Generates the stock Java declarations covered by the C# compile gate. */
final class GenerateStockCSharpModels {
    private GenerateStockCSharpModels() { }

    public static void main(String[] args) throws IOException {
        Path repository = Path.of(args[0]);
        File destination = Path.of(args[1]).toFile();
        var packages = Files.readAllLines(Path.of(args[2]));
        checkCoverage(repository, packages);
        for (String declaration : packages) {
            try (var sources = Files.list(repository.resolve(declaration))) {
                File[] models = sources.filter(source -> source.getFileName().toString().endsWith(".java"))
                        .sorted().map(Path::toFile).toArray(File[]::new);
                VMF.generate(GenerationTarget.CSHARP, destination, models);
            } catch (RuntimeException | IOException error) {
                throw new IOException("C# model generation failed for " + declaration, error);
            }
        }
        System.out.println("Generated C# from " + packages.size() + " stock Java declaration packages.");
    }

    private static void checkCoverage(Path repository, List<String> packages) throws IOException {
        var discovered = new TreeSet<String>();
        for (String root : List.of("jackson/src/test/vmf", "test-suite/src/test/vmf")) {
            try (var paths = Files.walk(repository.resolve(root))) {
                paths.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".java"))
                        .map(Path::getParent)
                        .filter(parent -> parent.getFileName().toString().equals("vmfmodel"))
                        .map(parent -> repository.relativize(parent).toString().replace(File.separatorChar, '/'))
                        .forEach(discovered::add);
            }
        }
        var listed = new TreeSet<>(packages);
        if (listed.size() != packages.size()) {
            throw new IOException("The stock C# package list contains duplicate paths.");
        }
        var missing = new TreeSet<>(discovered);
        missing.removeAll(listed);
        var extra = new TreeSet<>(listed);
        extra.removeAll(discovered);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IOException("Stock C# package coverage differs. Missing: " + missing + ", extra: " + extra);
        }
    }
}
