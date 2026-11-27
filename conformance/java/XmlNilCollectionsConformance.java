package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.collectiondefaults.SchemaLiteralSamples;
import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyContainer;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class XmlNilCollectionsConformance {
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    private XmlNilCollectionsConformance() {}

    static void verify(ObjectMapper withoutReferences, ObjectMapper references) throws IOException {
        for (String profile : List.of("plain", "typed", "references")) {
            var reader = profile.equals("references") ? references : withoutReferences;
            var omitted = read(reader, profile, SchemaLiteralSamples.class, "");
            check(omitted.getValues().equals(List.of(7, 11)) && omitted.getCodes().equals(Set.of(2L))
                    && omitted.getChoices().equals(Map.of(4, "ready")), "Omitted wrappers retain collection defaults");
            verifyDefaultsPresence(omitted, false);

            for (String wrapper : List.of("", " xsi:nil=\"true\"", " xsi:nil=\"1\"")) {
                var model = read(reader, profile, CollectionProfile.class,
                        empty(wrapper, "messages", "counts", "phases", "levels", "options"));
                check(model.getMessages().isEmpty() && model.getCounts().isEmpty() && model.getPhases().isEmpty()
                        && model.getLevels().isEmpty() && model.getOptions().isEmpty(), "Empty scalar collection wrappers");
                check(read(reader, profile, DefaultSettings.class, empty(wrapper, "entries")).getEntries().isEmpty(),
                        "Empty ordered ownership wrapper");
                check(read(reader, profile, LegacyContainer.class, empty(wrapper, "children")).getChildren().isEmpty(),
                        "Empty ordinary ownership wrapper");

                var cleared = read(reader, profile, SchemaLiteralSamples.class, empty(wrapper, "values", "codes", "choices"));
                check(cleared.getValues().isEmpty() && cleared.getCodes().isEmpty() && cleared.getChoices().isEmpty(),
                        "Present empty wrappers clear declared defaults");
                verifyDefaultsPresence(cleared, true);
            }

            var members = read(reader, profile, CollectionProfile.class,
                    "<messages xsi:nil=\"false\"><item xsi:nil=\"true\"/><item></item><item>hello</item></messages>"
                    + "<levels xsi:nil=\"0\"><member><value>2</value></member></levels>"
                    + "<options xsi:nil=\"false\"><entry><key>present</key><null>true</null></entry></options>");
            check(members.getMessages().equals(Arrays.asList(null, "", "hello")) && members.getLevels().equals(Set.of(2.0))
                    && members.getOptions().containsKey("present") && members.getOptions().get("present") == null,
                    "False nil wrappers and nullable member values");
            var owner = read(reader, profile, LegacyContainer.class, "<children><item xsi:nil=\"true\"/></children>");
            check(owner.getChildren().size() == 1 && owner.getChildren().get(0) == null, "Nullable ordinary owning slot");
        }
    }

    private static String empty(String attributes, String... fields) {
        var xml = new StringBuilder();
        for (String field : fields) xml.append('<').append(field).append(attributes).append("/>");
        return xml.toString();
    }

    private static void verifyDefaultsPresence(SchemaLiteralSamples model, boolean isSet) {
        for (String field : List.of("values", "codes", "choices")) {
            check(model.vmf().reflect().propertyByName(field).orElseThrow().isSet() == isSet, "Default state of " + field);
        }
    }

    private static <T> T read(ObjectMapper reader, String profile, Class<T> type, String fields) throws IOException {
        String root = type.getName();
        String metadata = profile.equals("plain") ? "" : "<vmf-type>" + root + "</vmf-type>";
        if (profile.equals("references")) metadata += "<vmf-id>1</vmf-id>";
        return reader.readValue("<" + root + " xmlns:xsi=\"" + XSI + "\">" + metadata + fields + "</" + root + ">", type);
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
