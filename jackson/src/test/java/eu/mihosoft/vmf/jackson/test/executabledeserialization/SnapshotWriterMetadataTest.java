package eu.mihosoft.vmf.jackson.test.executabledeserialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFTypeUtils;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotWriterMetadataTest {
    private static final Map<String, String> ALIASES = Map.of(
            "referenced-child", ReferencedChild.class.getName(),
            "tagged-child", TaggedChild.class.getName());

    @Test
    void aliasDiscoveryUsesStaticDeclarationsOfTheRequestedModelLoader() throws Exception {
        try (var loader = isolatedModelLoader()) {
            var model = loader.loadClass(HookedValues.class.getName()).asSubclass(VObject.class);
            assertEquals(ALIASES, VMFTypeUtils.modelWireTypeAliases(model));
            assertEquals(ALIASES, VMFTypeUtils.modelWireTypeAliases(model));
            module(false).withModelWireTypeAliases(model);
            assertEquals(List.of(), events(loader));

            var collision = module(false).withTypeAlias("tagged-child", HookedValues.class.getName());
            assertThrows(IllegalArgumentException.class, () -> collision.withModelWireTypeAliases(model));
            assertEquals(List.of(), events(loader));
        }
    }

    @Test
    void coldWritersUseNoInspectionBuildAndKeepPolymorphicValuesAndReferences() throws Exception {
        for (boolean references : List.of(false, true)) {
            try (var loader = isolatedModelLoader()) {
                var rootType = loader.loadClass(HookedReferences.class.getName()).asSubclass(VObject.class);
                var childType = loader.loadClass(ReferencedChild.class.getName());
                var taggedType = loader.loadClass(TaggedChild.class.getName());
                var root = rootType.getMethod("newInstance").invoke(null);
                var child = taggedType.getMethod("newInstance").invoke(null);
                childType.getMethod("setLabel", String.class).invoke(child, "child");
                taggedType.getMethod("setTag", String.class).invoke(child, "tag");
                rootType.getMethod("setChild", childType).invoke(root, child);
                if (references) rootType.getMethod("setSelection", childType).invoke(root, child);

                // Keep alias discovery out of this cold serialization path.
                var mapper = new ObjectMapper().registerModule(module(references).withTypeAliases(ALIASES));
                reset(loader);
                String document = mapper.writeValueAsString(root);
                assertEquals(List.of(), events(loader));
                assertEquals(document, mapper.writeValueAsString(root));
                assertEquals(List.of(), events(loader));

                var tree = mapper.readTree(document);
                assertEquals("tagged-child", tree.at("/child/@vmf-type").asText());
                assertEquals("tag", tree.at("/child/tag").asText());
                if (references) {
                    assertTrue(tree.at("/child/@vmf-id").isTextual());
                    assertEquals(tree.at("/child/@vmf-id"), tree.get("picked"));
                } else {
                    assertFalse(tree.get("child").has("@vmf-id"));
                    assertFalse(tree.has("picked"));
                }

                var copy = mapper.readValue(document, rootType);
                var copyChild = rootType.getMethod("getChild").invoke(copy);
                assertTrue(taggedType.isInstance(copyChild));
                assertFalse(TaggedChild.class.isInstance(copyChild));
                if (references) assertSame(copyChild, rootType.getMethod("getSelection").invoke(copy));
            }
        }
    }

    @Test
    void typeCatalogPreservesClosedApplicationsWithoutConstructingModels() throws Exception {
        try (var loader = isolatedModelLoader()) {
            var actual = type(loader, HookedMetadataPort.class);
            var text = type(loader, TextMetadataPort.class);
            var number = type(loader, NumberMetadataPort.class);

            var textApplication = text.superTypes().get(0);
            assertEquals(MetadataPort.class.getName(), textApplication.getName());
            assertEquals(TextMetadataReading.class.getName(), textApplication.getDescriptor().getArguments().get(0).getName());
            assertEquals(List.of(text), actual.superTypes());
            assertTrue(actual.isModelSubtypeOf(textApplication));
            assertFalse(number.isModelSubtypeOf(textApplication));
            assertFalse(actual.isModelSubtypeOf(TextMetadataPort.type().superTypes().get(0)));
            assertEquals(List.of(), events(loader));

            var reflection = actual.reflect();
            assertSame(reflection, actual.reflect());
            assertEquals(List.of("port-hook"), events(loader));
            assertThrows(RuntimeException.class,
                    () -> reflection.propertyByName("note").orElseThrow().getDefault());
        }
    }

    private static Type type(ClassLoader loader, Class<?> declaration) throws ReflectiveOperationException {
        return (Type) loader.loadClass(declaration.getName()).getMethod("type").invoke(null);
    }

    private static VMFJacksonModule module(boolean references) {
        return VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references);
    }

    private static URLClassLoader isolatedModelLoader() {
        String packagePrefix = HookedValues.class.getPackageName() + ".";
        ClassLoader parent = new ClassLoader(HookedValues.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(packagePrefix)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        URL classes = HookedValues.class.getProtectionDomain().getCodeSource().getLocation();
        return new URLClassLoader(new URL[]{classes}, parent);
    }

    private static void reset(ClassLoader loader) throws ReflectiveOperationException {
        loader.loadClass(DefaultTrace.class.getName()).getMethod("reset").invoke(null);
    }

    private static Object events(ClassLoader loader) throws ReflectiveOperationException {
        return loader.loadClass(DefaultTrace.class.getName()).getMethod("trace").invoke(null);
    }
}
