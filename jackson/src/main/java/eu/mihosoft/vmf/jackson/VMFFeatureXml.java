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
 */
package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Readable XML representation of the shared feature document. */
final class VMFFeatureXml {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String ROOT = "vmf-features";
    private static final String TEXT_ENCODING = "utf16be-base64";

    private VMFFeatureXml() {
    }

    static void write(OutputStream stream, ObjectNode envelope) throws IOException {
        try {
            XMLStreamWriter output = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(stream, "UTF-8");
            try {
                output.writeStartDocument("UTF-8", "1.0");
                start(output, ROOT, 0);
                output.writeAttribute("version", envelope.get("version").asText());
                writeEntry(output, "owner", envelope.get("owner"), 1);
                start(output, "features", 1);
                JsonNode features = envelope.get("features");
                for (JsonNode feature : features) {
                    writeEntry(output, "feature", feature, 2);
                }
                end(output, features, 1);
                indent(output, 0);
                output.writeEndElement();
                output.writeCharacters("\n");
                output.writeEndDocument();
                output.flush();
            } finally {
                output.close();
            }
        } catch (XMLStreamException exception) {
            throw new IOException("Could not write VMF feature XML", exception);
        }
    }

    private static void writeEntry(XMLStreamWriter output, String tag, JsonNode entry, int depth)
            throws XMLStreamException, IOException {
        start(output, tag, depth);
        output.writeAttribute("type", entry.get("type").textValue());
        JsonNode value = entry.get("value");
        writeProperties(output, value, depth + 1);
        end(output, value, depth);
    }

    private static void writeProperties(XMLStreamWriter output, JsonNode object, int depth)
            throws XMLStreamException, IOException {
        var fields = object.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            start(output, "property", depth);
            String name = field.getKey();
            if (name.codePoints().allMatch(c -> c != '\t' && c != '\n' && preservesText(c))) {
                output.writeAttribute("name", name);
            } else {
                output.writeAttribute("name", encodeText(name));
                output.writeAttribute("name-encoding", TEXT_ENCODING);
            }
            writeValue(output, field.getValue(), depth);
        }
    }

    private static void writeValue(XMLStreamWriter output, JsonNode value, int depth)
            throws XMLStreamException, IOException {
        String kind = value.isNumber()
                ? value.numberType().name().toLowerCase(Locale.ROOT).replace('_', '-')
                : value.getNodeType().name().toLowerCase(Locale.ROOT);
        output.writeAttribute("kind", kind);
        switch (value.getNodeType()) {
            case OBJECT:
                writeProperties(output, value, depth + 1);
                end(output, value, depth);
                return;
            case ARRAY:
                for (JsonNode item : value) {
                    start(output, "item", depth + 1);
                    writeValue(output, item, depth + 1);
                }
                end(output, value, depth);
                return;
            case STRING:
                String text = value.textValue();
                if (!text.codePoints().allMatch(VMFFeatureXml::preservesText)) {
                    output.writeAttribute("encoding", TEXT_ENCODING);
                    text = encodeText(text);
                }
                output.writeCharacters(text);
                break;
            case BINARY:
                output.writeCharacters(Base64.getEncoder().encodeToString(value.binaryValue()));
                break;
            case BOOLEAN:
            case NUMBER:
                output.writeCharacters(value.asText());
                break;
            case NULL:
                break;
            default:
                throw new IOException("Unsupported VMF XML value kind: " + kind);
        }
        output.writeEndElement();
    }

    private static void start(XMLStreamWriter output, String name, int depth) throws XMLStreamException {
        indent(output, depth);
        output.writeStartElement(name);
    }

    private static void end(XMLStreamWriter output, JsonNode contents, int depth) throws XMLStreamException {
        if (!contents.isEmpty()) indent(output, depth);
        output.writeEndElement();
    }

    private static void indent(XMLStreamWriter output, int depth) throws XMLStreamException {
        output.writeCharacters("\n" + "  ".repeat(depth));
    }

    private static boolean preservesText(int codePoint) {
        // XML forbids some characters and normalizes literal carriage returns.
        return codePoint == '\t' || codePoint == '\n'
                || (codePoint >= 0x20 && codePoint <= 0xd7ff)
                || (codePoint >= 0xe000 && codePoint <= 0xfffd)
                || codePoint >= 0x10000;
    }

    private static String encodeText(String text) {
        ByteBuffer bytes = ByteBuffer.allocate(Character.BYTES * text.length());
        bytes.asCharBuffer().put(text);
        return Base64.getEncoder().encodeToString(bytes.array());
    }

    static ObjectNode read(InputStream stream) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",
                    Integer.toString(StreamReadConstraints.defaults().getMaxNestingDepth()));
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return readEnvelope(builder.parse(stream).getDocumentElement());
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Invalid VMF feature XML: " + exception.getMessage(), exception);
        }
    }

    private static ObjectNode readEnvelope(Element root) throws IOException {
        requireName(root, ROOT);
        requireAttributes(root, "version");
        int version;
        try {
            version = Integer.parseInt(requiredAttribute(root, "version"));
        } catch (NumberFormatException exception) {
            throw invalid(root, "expected an integer version");
        }
        List<Element> children = childElements(root);
        if (children.size() != 2) throw invalid(root, "expected <owner> and <features>");
        Element owner = children.get(0);
        Element features = children.get(1);
        requireName(owner, "owner");
        requireName(features, "features");
        requireAttributes(features);

        ObjectNode envelope = NODES.objectNode().put("version", version);
        envelope.set("owner", readEntry(owner));
        ArrayNode entries = envelope.putArray("features");
        for (Element feature : childElements(features)) {
            requireName(feature, "feature");
            entries.add(readEntry(feature));
        }
        return envelope;
    }

    private static ObjectNode readEntry(Element element) throws IOException {
        requireAttributes(element, "type");
        ObjectNode entry = NODES.objectNode().put("type", requiredAttribute(element, "type"));
        entry.set("value", readProperties(element));
        return entry;
    }

    private static ObjectNode readProperties(Element element) throws IOException {
        ObjectNode result = NODES.objectNode();
        for (Element child : childElements(element)) {
            requireName(child, "property");
            requireAttributes(child, "name", "name-encoding", "kind", "encoding");
            String name = readText(child, requiredAttribute(child, "name"), "name-encoding");
            if (result.has(name)) throw invalid(child, "duplicate property '" + name + "'");
            result.set(name, readValue(child));
        }
        return result;
    }

    private static JsonNode readValue(Element element) throws IOException {
        String kind = requiredAttribute(element, "kind");
        if (!"string".equals(kind) && element.hasAttribute("encoding")) {
            throw invalid(element, "only strings may specify a text encoding");
        }
        switch (kind) {
            case "object":
                return readProperties(element);
            case "array":
                ArrayNode items = NODES.arrayNode();
                for (Element item : childElements(element)) {
                    requireName(item, "item");
                    requireAttributes(item, "kind", "encoding");
                    items.add(readValue(item));
                }
                return items;
            case "string":
                return NODES.textNode(readText(element, leafText(element), "encoding"));
            default:
                return readScalar(element, kind, leafText(element).trim());
        }
    }

    private static JsonNode readScalar(Element element, String kind, String text) throws IOException {
        try {
            switch (kind) {
                case "null":
                    if (!text.isEmpty()) throw invalid(element, "null must be empty");
                    return NODES.nullNode();
                case "boolean":
                    if (!"true".equals(text) && !"false".equals(text)) {
                        throw invalid(element, "expected true or false");
                    }
                    return NODES.booleanNode(Boolean.parseBoolean(text));
                case "int":
                    return NODES.numberNode(Integer.parseInt(text));
                case "long":
                    return NODES.numberNode(Long.parseLong(text));
                case "big-integer":
                    return NODES.numberNode(new BigInteger(text));
                case "float":
                    return NODES.numberNode(Float.parseFloat(text));
                case "double":
                    return NODES.numberNode(Double.parseDouble(text));
                case "big-decimal":
                    return NODES.numberNode(new BigDecimal(text));
                case "binary":
                    return NODES.binaryNode(Base64.getDecoder().decode(text));
                default:
                    throw invalid(element, "unknown value kind '" + kind + "'");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(element, "invalid " + kind + " value");
        }
    }

    private static String readText(Element element, String text, String encodingAttribute) throws IOException {
        if (!element.hasAttribute(encodingAttribute)) return text;
        if (!TEXT_ENCODING.equals(element.getAttribute(encodingAttribute))) {
            throw invalid(element, "unknown text encoding");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(text);
            if (bytes.length % Character.BYTES != 0) throw invalid(element, "invalid encoded text length");
            return ByteBuffer.wrap(bytes).asCharBuffer().toString();
        } catch (IllegalArgumentException exception) {
            throw invalid(element, "expected base64 text");
        }
    }

    private static List<Element> childElements(Element element) throws IOException {
        List<Element> children = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) {
                children.add((Element) node);
            } else if (node.getNodeType() != Node.COMMENT_NODE
                    && (node.getNodeType() != Node.TEXT_NODE || !node.getNodeValue().isBlank())) {
                throw invalid(element, "expected only child elements");
            }
        }
        return children;
    }

    private static String leafText(Element element) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            switch (node.getNodeType()) {
                case Node.TEXT_NODE:
                case Node.CDATA_SECTION_NODE:
                    text.append(node.getNodeValue());
                    break;
                case Node.COMMENT_NODE:
                    break;
                default:
                    throw invalid(element, "expected only text");
            }
        }
        return text.toString();
    }

    private static void requireName(Element element, String name) throws IOException {
        if (element.getNamespaceURI() != null || !name.equals(element.getTagName())) {
            throw invalid(element, "expected <" + name + ">");
        }
    }

    private static void requireAttributes(Element element, String... allowedNames) throws IOException {
        var names = List.of(allowedNames);
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (attribute.getNamespaceURI() != null || !names.contains(attribute.getNodeName())) {
                throw invalid(element, "unexpected attribute '" + attribute.getNodeName() + "'");
            }
        }
    }

    private static String requiredAttribute(Element element, String attribute) throws IOException {
        if (!element.hasAttribute(attribute)) throw invalid(element, "missing attribute '" + attribute + "'");
        return element.getAttribute(attribute);
    }

    private static IOException invalid(Element element, String message) {
        return new IOException("Invalid VMF feature XML at <" + element.getTagName() + ">: " + message);
    }
}
