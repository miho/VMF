package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ReadOnlyArchiveNode;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFFeatureCodec;
import eu.mihosoft.vmf.runtime.core.FeatureAttachments;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class FeatureBundleDepthConformance {
    private FeatureBundleDepthConformance() { }

    static void exchange(Path directory, boolean write, ModelRegistry registry) throws IOException {
        var codec = new VMFFeatureCodec(registry);
        for (var format : VMFDocumentFormat.values()) {
            for (boolean feature : new boolean[]{false, true}) {
                int depth = feature && format != VMFDocumentFormat.XML ? 497 : 498;
                String entry = feature ? "feature" : "owner";
                var path = directory.resolve((write ? "java" : "csharp") + "-depth-" + entry + "."
                        + format.name().toLowerCase(Locale.ROOT));
                if (write) {
                    save(codec, path, format, registry, depth, feature);
                    String accepted = Files.readString(path);
                    try {
                        save(codec, path, format, registry, depth + 1, feature);
                        throw new AssertionError("Oversized " + format + " bundle replaced an existing " + entry);
                    } catch (IOException expected) {
                        check(Files.readString(path).equals(accepted), "Oversized save preserves the prior bundle");
                    }
                    try (var files = Files.list(directory)) {
                        check(files.noneMatch(file -> file.getFileName().toString().startsWith(".vmf-features-")),
                                "An unsuccessful save removes its temporary file");
                    }
                }
                verify(codec.load(path, ArchiveNode.class, format), depth, feature);
            }
        }
    }

    private static void save(VMFFeatureCodec codec, Path path, VMFDocumentFormat format, ModelRegistry registry,
                             int depth, boolean feature) throws IOException {
        var tail = ArchiveNode.newBuilder().withName("tail").build();
        var tree = tail;
        for (int link = depth; link > 0; link--) {
            var parent = ArchiveNode.newBuilder().withName("parent").build();
            parent.getChildren().add(tree);
            tree = parent;
        }
        tree.setAnchor(tail);
        ReadOnlyArchiveNode owner = feature ? ArchiveNode.newInstance().asReadOnly() : tree.asReadOnly();
        var attachments = new FeatureAttachments(registry);
        if (feature) attachments.attach(owner, tree.asReadOnly());
        codec.save(path, owner, attachments, format);
    }

    private static void verify(VMFFeatureCodec.Document<ArchiveNode> bundle, int depth, boolean feature) {
        var owner = bundle.owner();
        var attachments = bundle.attachments();
        var tree = feature ? attachments.find(owner, ArchiveNode.class).orElseThrow() : owner;
        check(attachments.all(owner).size() == (feature ? 1 : 0)
                        && attachments.all(owner.asReadOnly()).isEmpty(), "The restored bundle retains its exact attachment key");
        var tail = tree;
        for (int link = depth; link > 0; link--) {
            var parent = tail;
            check(parent.getChildren().size() == 1, "Visible containment link survives transport");
            tail = parent.getChildren().get(0);
            check(tail.getParent() == parent, "The copied child has its original inverse");
        }
        check(tree.getAnchor() == tail && "tail".equals(tail.getName()), "The terminal alias survives inside its entry");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
