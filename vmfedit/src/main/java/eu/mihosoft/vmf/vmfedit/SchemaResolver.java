package eu.mihosoft.vmf.vmfedit;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Interface for custom schema resolution strategies.
 */
public interface SchemaResolver {
    /**
     * Resolves a schema URI to its content.
     *
     * @param uri The URI to resolve
     * @return The schema content as a string, or null if the schema cannot be resolved
     * @throws IOException if there is an error reading the schema
     */
    String resolveSchema(URI uri) throws IOException;

    /**
     * Checks if this resolver can handle the given URI.
     *
     * @param uri The URI to check
     * @return true if this resolver can handle the URI, false otherwise
     */
    boolean canHandle(URI uri);


    /**
     * Default file-based schema resolver.
     */
    public static class FileSchemaResolver implements SchemaResolver {
        @Override
        public String resolveSchema(URI uri) throws IOException {
            return Files.readString(Path.of(uri));
        }

        @Override
        public boolean canHandle(URI uri) {
            return uri.getScheme() == null || uri.getScheme().equals("file");
        }
    }

    /**
     * Resource-based schema resolver for embedded schemas.
     */
    public static class ResourceSchemaResolver implements SchemaResolver {
        private final ClassLoader classLoader;
        private final String basePath;

        public ResourceSchemaResolver(ClassLoader classLoader, String basePath) {
            this.classLoader = classLoader;
            this.basePath = basePath.endsWith("/") ? basePath : basePath + "/";
        }

        @Override
        public String resolveSchema(URI uri) throws IOException {
            String resourcePath = basePath + uri.getPath();
            var resource = classLoader.getResourceAsStream(resourcePath);
            if (resource == null) {
                throw new IOException("Schema resource not found: " + resourcePath);
            }
            return new String(resource.readAllBytes());
        }

        @Override
        public boolean canHandle(URI uri) {
            return uri.getScheme() != null && uri.getScheme().equals("resource");
        }
    }

    /**
     * In-memory schema resolver for predefined schemas.
     */
    public static class InMemorySchemaResolver implements SchemaResolver {
        private final Map<URI, String> schemaMap = new HashMap<>();

        public void registerSchema(URI uri, String content) {
            schemaMap.put(uri, content);
        }

        @Override
        public String resolveSchema(URI uri) throws IOException {
            String content = schemaMap.get(uri);
            if (content == null) {
                throw new IOException("Schema not found: " + uri);
            }
            return content;
        }

        @Override
        public boolean canHandle(URI uri) {
            return uri.getScheme() != null && uri.getScheme().equals("memory");
        }
    }

    /**
     * Composite resolver that tries multiple resolvers in sequence.
     */
    public static class CompositeSchemaResolver implements SchemaResolver {
        private final SchemaResolver[] resolvers;

        public CompositeSchemaResolver(SchemaResolver... resolvers) {
            this.resolvers = resolvers;
        }

        @Override
        public String resolveSchema(URI uri) throws IOException {
            for (SchemaResolver resolver : resolvers) {
                if (resolver.canHandle(uri)) {
                    return resolver.resolveSchema(uri);
                }
            }
            throw new IOException("No resolver found for schema: " + uri);
        }

        @Override
        public boolean canHandle(URI uri) {
            for (SchemaResolver resolver : resolvers) {
                if (resolver.canHandle(uri)) {
                    return true;
                }
            }
            return false;
        }
    }
}