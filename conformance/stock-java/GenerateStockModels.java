package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Generates the stock Java declarations (every model package of the test-suite and of vmf-jackson) for the compile
 * gate of a target: C# compiles the output with warnings as errors, TypeScript type-checks it.
 * Arguments: target (csharp, typescript), repository, destination, package list.
 */
final class GenerateStockModels {
    private GenerateStockModels() { }

    public static void main(String[] args) throws IOException {
        GenerationTarget target = GenerationTarget.valueOf(args[0].toUpperCase(Locale.ROOT));
        Path repository = Path.of(args[1]);
        File destination = Path.of(args[2]).toFile();
        var packages = Files.readAllLines(Path.of(args[3]));
        checkCoverage(repository, packages);
        for (String declaration : packages) {
            try (var sources = Files.list(repository.resolve(declaration))) {
                File[] models = sources.filter(source -> source.getFileName().toString().endsWith(".java"))
                        .sorted().map(Path::toFile).toArray(File[]::new);
                VMF.generate(target, destination, models);
            } catch (RuntimeException | IOException error) {
                throw new IOException(target + " model generation failed for " + declaration, error);
            }
        }
        System.out.println("Generated " + target + " from " + packages.size() + " stock Java declaration packages.");
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
            throw new IOException("The stock package list contains duplicate paths.");
        }
        var missing = new TreeSet<>(discovered);
        missing.removeAll(listed);
        var extra = new TreeSet<>(listed);
        extra.removeAll(discovered);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IOException("Stock package coverage differs. Missing: " + missing + ", extra: " + extra);
        }
    }
}
