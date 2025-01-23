package eu.mihosoft.vmf.vmfedit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;

public final class JsonUtils {

    private static SchemaResolver defaultResolver = new SchemaResolver.CompositeSchemaResolver(
            new SchemaResolver.FileSchemaResolver(),
            new SchemaResolver.ResourceSchemaResolver(JsonUtils.class.getClassLoader(), "schemas"),
            new SchemaResolver.InMemorySchemaResolver()
    );


    private JsonUtils() {
        throw new AssertionError("Don't instantiate me!");
    }

    enum SchemaEmbeddingType {
        EXTERNAL, EMBEDDED, EMBEDDED_DEFINITIONS, NONE
    }

    public record SchemaInfo(URI schemaUri, String schemaContent, SchemaEmbeddingType embeddingType ) {}

    /**
     * Extracts schema information from JSON content.
     *
     * @param jsonContent The JSON content to analyze
     * @param baseUri Optional base URI for resolving relative schema paths (can be null)
     * @param resolver Optional schema resolver (if null, uses default resolver)
     * @return A record containing the schema URI (if external) and schema content (if embedded)
     * @throws IOException if schema loading fails
     */
    public static SchemaInfo extractSchema(String jsonContent, URI baseUri, SchemaResolver resolver) throws IOException {
        SchemaResolver effectiveResolver = resolver != null ? resolver : defaultResolver;

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonContent);

        // Case 1: Check for $schema URI reference
        if (rootNode.has("$schema") && rootNode.get("$schema").isTextual()) {
            String schemaRef = rootNode.get("$schema").asText();
            try {
                URI schemaUri;
//                if (schemaRef.startsWith("http://") || schemaRef.startsWith("https://")
//                        || schemaRef.startsWith("file:/") || schemaRef.startsWith("resource:/")
//                        || schemaRef.startsWith("memory:/")) {

                boolean canHandle;
                try {
                    canHandle = effectiveResolver.canHandle(new URI(schemaRef));
                } catch (URISyntaxException e) {
                    canHandle = false;
                }
                if(canHandle) {
                    schemaUri = new URI(schemaRef);
                } else if (baseUri != null) {
                    // Resolve relative path against baseUri
                    schemaUri = baseUri.resolve(schemaRef);
                } else {
                    // Relative path without baseUri - return as-is
                    schemaUri = new URI(schemaRef);
                }

                if (effectiveResolver.canHandle(schemaUri)) {
                    String schemaContent = effectiveResolver.resolveSchema(schemaUri);
                    return new SchemaInfo(schemaUri, schemaContent, SchemaEmbeddingType.EXTERNAL);
                }

                return new SchemaInfo(schemaUri, null, SchemaEmbeddingType.EXTERNAL);
            } catch (URISyntaxException e) {
                throw new IOException("Invalid schema URI: " + schemaRef, e);
            }
        }

        // Case 2: Check for embedded schema object
        if (rootNode.has("$schema") && rootNode.get("$schema").isObject()) {
            return new SchemaInfo(null, rootNode.get("$schema").toString(), SchemaEmbeddingType.EMBEDDED);
        }

        // Case 3: Check for schema in definitions
        if (rootNode.has("definitions")) {
            JsonNode definitions = rootNode.get("definitions");
            // Look for any $ref usage in the root properties
            if (rootNode.has("properties")) {
                JsonNode properties = rootNode.get("properties");
                if (properties.isObject()) {
                    Iterator<JsonNode> elements = properties.elements();
                    while (elements.hasNext()) {
                        JsonNode prop = elements.next();
                        if (prop.has("$ref") && prop.get("$ref").asText().startsWith("#/definitions/")) {
                            // Found a reference to definitions, return the whole schema structure
                            ObjectNode schemaNode = mapper.createObjectNode();
                            schemaNode.set("definitions", definitions);
                            schemaNode.set("type", rootNode.get("type"));
                            schemaNode.set("properties", properties);
                            return new SchemaInfo(null, schemaNode.toString(), SchemaEmbeddingType.EMBEDDED_DEFINITIONS);
                        }
                    }
                }
            }
        }

        // Case 4: No schema found
        return new SchemaInfo(null, null, SchemaEmbeddingType.NONE);
    }

    public static SchemaInfo extractSchema(String jsonContent, URI baseUri) throws IOException {
        return extractSchema(jsonContent, baseUri, null);
    }

    /**
     * Injects schema information into JSON content.
     *
     * @param jsonContent The JSON content to modify
     * @param schemaInfo The schema information to inject
     * @return The modified JSON content
     * @throws IOException if JSON parsing fails
     * @throws IllegalArgumentException if schemaInfo is null or invalid
     */
    public static String injectSchema(String jsonContent, SchemaInfo schemaInfo) throws IOException {
        if (schemaInfo == null) {
            throw new IllegalArgumentException("SchemaInfo cannot be null");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonContent);
        ObjectNode mutableRoot = (ObjectNode) rootNode;

        switch (schemaInfo.embeddingType()) {
            case EXTERNAL -> {
                if (schemaInfo.schemaUri() == null) {
                    throw new IllegalArgumentException("Schema URI cannot be null for EXTERNAL embedding type");
                }
                System.out.println("!!! Case External: Injecting schema URI: " + schemaInfo.schemaUri());
                mutableRoot.put("$schema", schemaInfo.schemaUri().toString());
            }
            case EMBEDDED -> {
                if (schemaInfo.schemaContent() == null) {
                    throw new IllegalArgumentException("Schema content cannot be null for EMBEDDED embedding type");
                }
                JsonNode schemaNode = mapper.readTree(schemaInfo.schemaContent());
                mutableRoot.set("$schema", schemaNode);
                System.out.println("!!! Case Embedded: Injecting schema content: " + schemaInfo.schemaContent());
            }
            case EMBEDDED_DEFINITIONS -> {
                if (schemaInfo.schemaContent() == null) {
                    throw new IllegalArgumentException("Schema content cannot be null for EMBEDDED_DEFINITIONS embedding type");
                }
                System.out.println("!!! Case Embedded Definitions: Injecting schema content: " + schemaInfo.schemaContent());
                JsonNode schemaNode = mapper.readTree(schemaInfo.schemaContent());

                // Extract and set definitions
                if (schemaNode.has("definitions")) {
                    mutableRoot.set("definitions", schemaNode.get("definitions"));
                }

                // Extract and set type if present
                if (schemaNode.has("type")) {
                    mutableRoot.set("type", schemaNode.get("type"));
                }

                // Extract and set properties if present
                if (schemaNode.has("properties")) {
                    mutableRoot.set("properties", schemaNode.get("properties"));
                }
            }
            case NONE -> {
                System.out.println("!!! Case None: Removing schema information");
                // Remove any existing schema-related fields
                mutableRoot.remove("$schema");
                mutableRoot.remove("definitions");
            }
        }

        return mapper.writeValueAsString(mutableRoot);
    }


    public static void main(String[] args) throws IOException {
        String input = "{\"name\": \"test\"}";
        SchemaInfo schemaInfo = new SchemaInfo(
                new File("C:/tmp/app-config-schema.json").toURI(),
                null,
                SchemaEmbeddingType.EXTERNAL
        );

        String result = JsonUtils.injectSchema(input, schemaInfo);
        JsonNode resultNode = new ObjectMapper().readTree(result);

        System.out.println(result);
    }
}
