/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.mihosoft.vmf.runtime.core.FeatureAttachments;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Saves one VMF owner and its directly attached features in a JSON, YAML, or XML document.
 * Each value is written as a VMF model object using its registered concrete type.
 * Containment and references inside each value follow the standard VMF Jackson rules.
 * Use the same registry, or a new registry with the same models, when loading.
 *
 * <pre>{@code
 * var codec = new VMFFeatureCodec(registry);
 * codec.save(path, device, attachments);
 * var loaded = codec.load(path, Device.class);
 * Device restored = loaded.owner();
 * var calibration = loaded.attachments().find(restored, Calibration.class);
 * }</pre>
 */
public final class VMFFeatureCodec {
    /**
     * Compatibility selector for callers using the original feature codec API.
     * @deprecated Use {@link VMFDocumentFormat}.
     */
    @Deprecated
    public enum Format {
        JSON(VMFDocumentFormat.JSON),
        YAML(VMFDocumentFormat.YAML),
        XML(VMFDocumentFormat.XML);

        private final VMFDocumentFormat documentFormat;

        Format(VMFDocumentFormat documentFormat) {
            this.documentFormat = documentFormat;
        }
    }

    private static final int VERSION = 1;
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final ModelRegistry registry;
    private final ObjectMapper mapper;

    /**
     * Creates a codec for the models installed in the application.
     * @param registry registry of the owner and feature models
     */
    public VMFFeatureCodec(ModelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        mapper = new ObjectMapper().registerModule(
                VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withModelRegistry(registry).withReferences(true));
    }

    /**
     * Writes the owner and its attached features. The filename extension selects the format, with JSON used for extensionless filenames.
     * @param file destination file
     * @param owner VMF object to save
     * @param attachments collection holding the owner's features
     * @throws IOException if an object cannot be encoded or the file cannot be written
     */
    public void save(Path file, VObject owner, FeatureAttachments attachments) throws IOException {
        save(file, owner, attachments, VMFDocumentFormat.fromPath(file));
    }

    /**
     * Writes the owner and its attached features in the selected format.
     * @param file destination file
     * @param owner VMF object to save
     * @param attachments collection holding the owner's features
     * @param format document format
     * @throws IOException if an object cannot be encoded or the file cannot be written
     */
    public void save(Path file, VObject owner, FeatureAttachments attachments, VMFDocumentFormat format)
            throws IOException {
        Objects.requireNonNull(format, "format");
        ObjectNode document = mapper.createObjectNode().put("version", VERSION);
        document.set("owner", encode(owner));
        ArrayNode features = document.putArray("features");
        for (VObject feature : attachments.all(owner)) {
            features.add(encode(feature));
        }
        writeFile(file, document, format);
    }

    /**
     * Loads a fresh owner and attaches newly constructed feature objects to it.
     * @param file source document, with the format selected by extension or JSON for an extensionless filename
     * @param ownerType expected Java type of the owner
     * @param <T> expected owner type
     * @return the restored owner and its new attachment collection
     * @throws IOException if the file is invalid or refers to an unregistered model type
     */
    public <T extends VObject> Document<T> load(Path file, Class<T> ownerType) throws IOException {
        return load(file, ownerType, VMFDocumentFormat.fromPath(file));
    }

    /**
     * Loads a fresh owner and its feature objects in the selected format.
     * @param file source document
     * @param ownerType expected Java type of the owner
     * @param format document format
     * @param <T> expected owner type
     * @return the restored owner and its new attachment collection
     * @throws IOException if the file is invalid or refers to an unregistered model type
     */
    public <T extends VObject> Document<T> load(Path file, Class<T> ownerType, VMFDocumentFormat format)
            throws IOException {
        Objects.requireNonNull(format, "format");
        JsonNode document;
        try (var input = Files.newInputStream(file)) {
            document = format == VMFDocumentFormat.XML ? VMFFeatureXml.read(input) : transportMapper(format).reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(input);
        }
        if (document == null || !document.isObject()) {
            throw new IOException("Expected a VMF feature document");
        }
        JsonNode version = document.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != VERSION) {
            throw new IOException("Unsupported VMF feature document version: " + version);
        }
        JsonNode features = document.path("features");
        if (!features.isArray()) {
            throw new IOException("Expected a 'features' array");
        }

        T owner = decode(document.path("owner"), ownerType);
        FeatureAttachments attachments = new FeatureAttachments(registry);
        for (JsonNode entry : features) {
            VObject feature = decode(entry, VObject.class);
            try {
                attachments.attach(owner, feature);
            } catch (IllegalArgumentException failure) {
                throw new IOException(failure.getMessage(), failure);
            }
        }
        return new Document<>(owner, attachments);
    }

    /**
     * Saves a feature document with the original feature codec format selector.
     * @param file destination file
     * @param owner VMF object to save
     * @param attachments collection holding the owner's features
     * @param format document format
     * @throws IOException if an object cannot be encoded or the file cannot be written
     * @deprecated Use {@link #save(Path, VObject, FeatureAttachments, VMFDocumentFormat)}.
     */
    @Deprecated
    public void save(Path file, VObject owner, FeatureAttachments attachments, Format format) throws IOException {
        save(file, owner, attachments, Objects.requireNonNull(format, "format").documentFormat);
    }

    /**
     * Loads a feature document with the original feature codec format selector.
     * @param file source document
     * @param ownerType expected Java type of the owner
     * @param format document format
     * @param <T> expected owner type
     * @return the restored owner and its attachment collection
     * @throws IOException if the source document is invalid or cannot be read
     * @deprecated Use {@link #load(Path, Class, VMFDocumentFormat)}.
     */
    @Deprecated
    public <T extends VObject> Document<T> load(Path file, Class<T> ownerType, Format format) throws IOException {
        return load(file, ownerType, Objects.requireNonNull(format, "format").documentFormat);
    }

    private ObjectNode encode(VObject object) throws IOException {
        Type type = registry.typeOf(object).orElseThrow(() -> new IOException(
                "Cannot save unregistered VMF type: " + object.vmf().reflect().type().getName()));
        ObjectNode entry = mapper.createObjectNode().put("type", type.getName());
        try {
            entry.set("value", mapper.valueToTree(object));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Cannot save VMF object of type " + type.getName(), failure);
        }
        return entry;
    }

    private <T extends VObject> T decode(JsonNode entry, Class<T> expectedType) throws IOException {
        JsonNode typeName = entry.path("type");
        JsonNode value = entry.path("value");
        if (!typeName.isTextual() || typeName.textValue().isBlank() || !value.isObject()) {
            throw new IOException("Expected a VMF object with a 'type' and 'value'");
        }
        String name = typeName.textValue();
        Type type = registry.findType(name).orElseThrow(() ->
                new IOException("Unregistered VMF type '" + name + "'. Register its model before loading."));
        Class<? extends VObject> javaType = registry.findJavaType(name).orElseThrow();
        if (type.isInterfaceOnly() || !expectedType.isAssignableFrom(javaType)) {
            throw new IOException("Cannot load VMF type '" + name + "' as " + expectedType.getName());
        }
        JsonNode discriminator = value.get("@vmf-type");
        if (discriminator != null && !typeName.equals(discriminator)) {
            throw new IOException("Payload type disagrees with registered VMF type '" + name + "'");
        }
        return expectedType.cast(mapper.treeToValue(value, javaType));
    }

    private ObjectMapper transportMapper(VMFDocumentFormat format) {
        switch (format) {
            case JSON: return mapper;
            case YAML: return YAML_MAPPER;
            default: throw new AssertionError(format);
        }
    }

    private void writeFile(Path file, ObjectNode document, VMFDocumentFormat format) throws IOException {
        Path destination = file.toAbsolutePath();
        Path temporary = Files.createTempFile(destination.getParent(), ".vmf-features-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                if (format == VMFDocumentFormat.XML) {
                    VMFFeatureXml.write(output, document);
                } else {
                    transportMapper(format).writerWithDefaultPrettyPrinter().writeValue(output, document);
                }
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** A loaded owner and the feature attachments associated with that exact instance. */
    public static final class Document<T extends VObject> {
        private final T owner;
        private final FeatureAttachments attachments;

        private Document(T owner, FeatureAttachments attachments) {
            this.owner = owner;
            this.attachments = attachments;
        }

        /**
         * Returns the restored owner.
         * @return restored owner
         */
        public T owner() {
            return owner;
        }

        /**
         * Returns the collection holding the owner's restored features.
         * @return restored attachment collection
         */
        public FeatureAttachments attachments() {
            return attachments;
        }
    }
}
