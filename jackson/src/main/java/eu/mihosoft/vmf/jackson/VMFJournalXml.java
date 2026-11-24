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
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Proceedings of the 2013 ACM workshop on Domain-Specific Modeling - DSM '13.
 * doi:10.1145/2541928.2541935
 */
package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
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
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Projects the journal envelope onto XML before the common wire validation runs. */
final class VMFJournalXml {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Map<String, String> ARRAY_ENTRIES = Map.ofEntries(
            Map.entry("transactions", "transaction"), Map.entry("operations", "operation"), Map.entry("indices", "index"),
            Map.entry("before", "value"), Map.entry("after", "value"), Map.entry("created", "creation"),
            Map.entry("values", "value"), Map.entry("beforeEntries", "entry"), Map.entry("afterEntries", "entry"),
            Map.entry("entries", "entry"), Map.entry("mapProperties", "mapProperty"));
    private static final Set<String> OBJECTS = Set.of("model", "base", "transaction", "operation", "creation", "value",
            "entry", "mapProperty");
    private static final Set<String> TEXT = Set.of("id", "root", "checkpoint", "kind", "target", "property",
            "type", "name", "codec", "text", "key");
    private static final Set<String> NUMBERS = Set.of("version", "sequence", "index");
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]{0,18})");

    private VMFJournalXml() {
    }

    static String write(ObjectNode envelope) throws IOException {
        StringWriter result = new StringWriter();
        try {
            XMLStreamWriter output = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(result);
            try {
                output.writeStartDocument("UTF-8", "1.0");
                writeElement(output, ChangeJournal.FORMAT, envelope, 0);
                output.writeEndDocument();
            } finally {
                output.close();
            }
            return result.toString();
        } catch (XMLStreamException exception) {
            throw new IOException("Could not write journal XML", exception);
        }
    }

    private static void writeElement(XMLStreamWriter output, String name, JsonNode value, int depth)
            throws XMLStreamException {
        indent(output, depth);
        output.writeStartElement(name);
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if ("properties".equals(name)) {
                    indent(output, depth + 1);
                    output.writeStartElement("property");
                    writeElement(output, "name", NODES.textNode(field.getKey()), depth + 2);
                    writeElement(output, "values", field.getValue(), depth + 2);
                    indent(output, depth + 1);
                    output.writeEndElement();
                } else if (!ChangeJournal.FORMAT.equals(name) || !"format".equals(field.getKey())) {
                    writeElement(output, field.getKey(), field.getValue(), depth + 1);
                }
            }
            if (!value.isEmpty()) indent(output, depth);
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                writeElement(output, ARRAY_ENTRIES.get(name), item, depth + 1);
            }
            if (!value.isEmpty()) indent(output, depth);
        } else if (value.isNull()) {
            output.writeAttribute("null", "true");
        } else {
            String text = value.asText();
            if (text.codePoints().allMatch(VMFJournalXml::preservesAsXmlText)) {
                output.writeCharacters(text);
            } else {
                output.writeAttribute("encoding", "base64");
                output.writeCharacters(Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
            }
        }
        output.writeEndElement();
    }

    static boolean preservesAsXmlText(int codePoint) {
        // XML normalizes literal carriage returns and disallows some otherwise valid journal characters.
        return codePoint == '\t' || codePoint == '\n'
                || (codePoint >= 0x20 && codePoint <= 0xd7ff)
                || (codePoint >= 0xe000 && codePoint <= 0xfffd)
                || codePoint >= 0x10000;
    }

    private static void indent(XMLStreamWriter output, int depth) throws XMLStreamException {
        output.writeCharacters("\n" + "  ".repeat(depth));
    }

    static JsonNode read(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "32");
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
            Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!ChangeJournal.FORMAT.equals(root.getTagName())) {
                throw invalid(root, "expected a <" + ChangeJournal.FORMAT + "> root");
            }
            return readObject(root).put("format", ChangeJournal.FORMAT);
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IOException("Invalid journal XML: " + exception.getMessage(), exception);
        }
    }

    private static JsonNode readElement(Element element) throws IOException {
        String name = element.getTagName();
        if (OBJECTS.contains(name)) return readObject(element);
        if (TEXT.contains(name)) return readText(element);
        if (NUMBERS.contains(name)) return readNumber(element);
        if ("properties".equals(name)) return readProperties(element);
        String itemName = ARRAY_ENTRIES.get(name);
        if (itemName == null) throw invalid(element, "unknown element");
        requireNoAttributes(element);
        ArrayNode items = NODES.arrayNode();
        for (Element child : childElements(element)) {
            if (!itemName.equals(child.getTagName())) throw invalid(child, "expected <" + itemName + ">");
            items.add(readElement(child));
        }
        return items;
    }

    private static ObjectNode readObject(Element element) throws IOException {
        requireNoAttributes(element);
        ObjectNode fields = NODES.objectNode();
        for (Element child : childElements(element)) {
            String name = child.getTagName();
            if (fields.has(name)) throw invalid(child, "duplicate field");
            fields.set(name, readElement(child));
        }
        return fields;
    }

    private static ObjectNode readProperties(Element element) throws IOException {
        requireNoAttributes(element);
        ObjectNode properties = NODES.objectNode();
        for (Element child : childElements(element)) {
            if (!"property".equals(child.getTagName())) throw invalid(child, "expected <property>");
            ObjectNode property = readObject(child);
            if (property.size() != 2 || !property.has("name") || !property.has("values")) {
                throw invalid(child, "expected a <name> and <values>");
            }
            String name = property.get("name").textValue();
            if (properties.has(name)) throw invalid(child, "duplicate property name");
            properties.set(name, property.get("values"));
        }
        return properties;
    }

    private static JsonNode readNumber(Element element) throws IOException {
        requireNoAttributes(element);
        String text = leafText(element).trim();
        if (!INTEGER.matcher(text).matches()) throw invalid(element, "expected a decimal integer");
        try {
            return NODES.numberNode(Long.parseLong(text));
        } catch (NumberFormatException exception) {
            throw invalid(element, "expected a 64-bit integer");
        }
    }

    private static JsonNode readText(Element element) throws IOException {
        String text = leafText(element);
        int attributes = element.getAttributes().getLength();
        if (attributes == 0) return NODES.textNode(text);
        if (attributes == 1 && "text".equals(element.getTagName())
                && "true".equals(element.getAttribute("null")) && text.isBlank()) {
            return NODES.nullNode();
        }
        if (attributes != 1 || !"base64".equals(element.getAttribute("encoding"))) {
            throw invalid(element, "unexpected attributes or null text");
        }
        try {
            var utf8 = ByteBuffer.wrap(Base64.getDecoder().decode(text));
            return NODES.textNode(StandardCharsets.UTF_8.newDecoder().decode(utf8).toString());
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw invalid(element, "expected UTF-8 base64 text");
        }
    }

    private static List<Element> childElements(Element element) throws IOException {
        List<Element> children = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) {
                children.add((Element) node);
            } else if (node.getNodeType() != Node.COMMENT_NODE
                    && (node.getNodeType() != Node.TEXT_NODE || !node.getNodeValue().trim().isEmpty())) {
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

    private static void requireNoAttributes(Element element) throws IOException {
        if (element.hasAttributes()) throw invalid(element, "unexpected attributes");
    }

    private static IOException invalid(Element element, String message) {
        return new IOException("Invalid journal XML at <" + element.getTagName() + ">: " + message);
    }
}
