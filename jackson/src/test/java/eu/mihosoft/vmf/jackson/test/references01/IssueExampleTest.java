package eu.mihosoft.vmf.jackson.test.references01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The example of miho/VMF#93: a selection (cross reference with opposite) next to containment.
 */
public class IssueExampleTest {

    private static Document document() {
        Document document = Document.newInstance();
        document.setId("document-1");

        Item item = Item.newInstance();
        item.setId("item-1");
        document.getItems().add(item);
        document.setSelectedItem(item);

        return document;
    }

    private static VMFJacksonModule module() {
        return VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL);
    }

    @Test
    void selectionAndItsOppositeRoundTripByIdentity() throws Exception {
        Document document = document();
        assertSame(document.getItems().get(0), document.getSelectedItem());
        assertSame(document, document.getItems().get(0).getSelectedBy());

        ObjectMapper mapper = new ObjectMapper().registerModule(module().withReferences(true));

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        Document copy = mapper.readValue(json, Document.class);

        assertSame(copy, copy.getItems().get(0).getDocument());
        assertSame(copy.getItems().get(0), copy.getSelectedItem());
        assertSame(copy, copy.getItems().get(0).getSelectedBy());

        // both objects are referenced: the item by the selection, the document by its opposite
        JsonNode tree = mapper.readTree(json);
        JsonNode item = tree.get("items").get(0);
        assertEquals("1", tree.get("@vmf-id").asText());
        assertEquals("2", item.get("@vmf-id").asText());
        assertEquals("2", tree.get("selectedItem").asText());
        assertEquals("1", item.get("selectedBy").asText());
        assertEquals("item-1", item.get("id").asText());
        assertFalse(item.has("document"), "the container is restored by containment, not written");

        // the representation documented in VMFJacksonModule#withReferences
        assertEquals("{\n"
                + "  \"@vmf-id\" : \"1\",\n"
                + "  \"id\" : \"document-1\",\n"
                + "  \"items\" : [ {\n"
                + "    \"@vmf-id\" : \"2\",\n"
                + "    \"id\" : \"item-1\",\n"
                + "    \"selectedBy\" : \"1\"\n"
                + "  } ],\n"
                + "  \"selectedItem\" : \"2\"\n"
                + "}", json.replace("\r\n", "\n"));

        // writing the copy gives the same JSON
        assertEquals(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(copy));
    }

    @Test
    void referencesAreNotWrittenByDefault() throws Exception {
        VMFJacksonModule module = module();
        assertFalse(module.isReferencesEnabled());

        ObjectMapper mapper = new ObjectMapper().registerModule(module);

        String json = mapper.writeValueAsString(document());
        assertEquals("{\"id\":\"document-1\",\"items\":[{\"id\":\"item-1\"}]}", json);

        Document copy = mapper.readValue(json, Document.class);
        assertSame(copy, copy.getItems().get(0).getDocument());
        assertNull(copy.getSelectedItem());
        assertNull(copy.getItems().get(0).getSelectedBy());
    }

    @Test
    void referencesCanBeSwitchedOff() {
        VMFJacksonModule module = module().withReferences(true);
        assertTrue(module.isReferencesEnabled());
        assertFalse(module.withReferences(false).isReferencesEnabled());
    }
}
