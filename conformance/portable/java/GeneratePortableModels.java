package eu.mihosoft.vmf.portable;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates every portable model package for one target. A package the target rejects is recorded in the
 * report instead of failing the build, so its scenarios count as failures of that target.
 *
 * <p>Usage: {@code GeneratePortableModels <java|csharp|typescript> <output-dir> <models-dir> <report-file>}</p>
 */
public final class GeneratePortableModels {

    private GeneratePortableModels() {
    }

    public static void main(String[] args) throws IOException {
        GenerationTarget target = GenerationTarget.valueOf(args[0].toUpperCase(Locale.ROOT));
        Path output = Paths.get(args[1]);
        Path models = Paths.get(args[2]);
        Path report = Paths.get(args[3]);
        Path staging = output.resolveSibling(output.getFileName() + "-staging");
        deleteTree(output);
        deleteTree(staging);
        Files.createDirectories(output);

        List<Path> packages;
        try (Stream<Path> paths = Files.walk(models)) {
            packages = paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equals("vmfmodel"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        StringBuilder unsupported = new StringBuilder();
        int generated = 0;
        for (Path declarations : packages) {
            String model = declarations.getParent().getFileName().toString();
            File[] sources;
            try (Stream<Path> files = Files.list(declarations)) {
                sources = files.filter(file -> file.toString().endsWith(".java"))
                        .sorted()
                        .map(Path::toFile)
                        .toArray(File[]::new);
            }
            Path modelOutput = staging.resolve(model);
            try {
                VMF.generate(target, modelOutput.toFile(), sources);
                copyTree(modelOutput, output);
                generated++;
            } catch (Exception | LinkageError error) {
                String reason = String.valueOf(error.getMessage()).replaceAll("\\s+", " ").trim();
                unsupported.append(model).append('\t').append(reason).append('\n');
                System.out.println("[" + target.name().toLowerCase(Locale.ROOT) + "] model '" + model
                        + "' not generated: " + reason);
            }
        }
        deleteTree(staging);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.write(report, unsupported.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[" + target.name().toLowerCase(Locale.ROOT) + "] generated " + generated + " of "
                + packages.size() + " portable models");
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.collect(Collectors.toList())) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(path);
            }
        }
    }
}
