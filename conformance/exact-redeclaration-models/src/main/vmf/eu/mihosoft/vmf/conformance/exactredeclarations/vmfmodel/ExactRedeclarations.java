package eu.mihosoft.vmf.conformance.exactredeclarations.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.InterfaceOnly;

interface Asset {
    String getId();
}

interface FirstRecord {
    @Annotation(key = "vmf:jackson:rename", value = "captionText")
    @DefaultValue("\"record\"")
    String getCaption();

    @Contains
    Asset[] getAssets();
}

interface SecondRecord {
    @Annotation(key = "vmf:jackson:rename", value = "captionText")
    @DefaultValue("\"record\"")
    String getCaption();

    @Contains
    Asset[] getAssets();
}

@InterfaceOnly
interface WithMarkers {
    @Contains
    String[] getMarkers();
}

interface CombinedRecord extends FirstRecord, SecondRecord, WithMarkers {
    @Override
    @Annotation(key = "vmf:jackson:rename", value = "captionText")
    @DefaultValue("\"record\"")
    String getCaption();

    @Override
    @Contains
    Asset[] getAssets();

    @Override
    String[] getMarkers();
}

interface RecordEnvelope {
    @Contains
    SecondRecord getRecord();
}

@InterfaceOnly
interface WithLegacyName {
    @Annotation(key = "vmf:jackson:rename", value = "label") String getName();
}

@InterfaceOnly
interface WithLegacyRank {
    @Annotation(key = "vmf:jackson:rename", value = "label") String getName();
    int getRank();
}

interface LocallyNamedRecord extends WithLegacyName, WithLegacyRank {
    @Override String getName();
}

interface LegacyNamedRecord extends WithLegacyName, WithLegacyRank { }
