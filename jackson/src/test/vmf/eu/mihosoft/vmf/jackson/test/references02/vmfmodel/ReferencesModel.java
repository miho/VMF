package eu.mihosoft.vmf.jackson.test.references02.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Refers;

/**
 * Every kind of non-containment reference next to containment (miho/VMF#93): sections and tags
 * are contained, the other model-typed properties are references.
 */
interface Project {
    String getName();

    @Contains(opposite = "project")
    Section[] getSections();

    @Contains(opposite = "project")
    Tag[] getTags();

    /** one-to-one cross reference */
    @Refers(opposite = "focusOf")
    Task getFocus();

    /** plain reference without opposite */
    Task getPinned();

    /** plain list of references without opposite (order and duplicates) */
    Task[] getHistory();

    /** immutable value: written by value, not as a reference */
    Version getVersion();
}

interface Section {
    String getName();

    @Container(opposite = "sections")
    Project getProject();

    @Contains(opposite = "section")
    Task[] getTasks();
}

interface Task {
    String getName();

    @Container(opposite = "tasks")
    Section getSection();

    @Refers(opposite = "focus")
    Project getFocusOf();

    /** many-to-many cross reference */
    @Refers(opposite = "tasks")
    Tag[] getTags();

    /** many-to-one cross reference (opposite is a list of the same type) */
    @Refers(opposite = "dependents")
    Task getDependsOn();

    @Refers(opposite = "dependsOn")
    Task[] getDependents();
}

/** subtype: references keep the type of their target */
interface Milestone extends Task {
    String getDate();
}

interface Tag {
    String getName();

    @Container(opposite = "tags")
    Project getProject();

    @Refers(opposite = "tags")
    Task[] getTasks();
}

@Immutable
interface Version {
    int getMajor();

    int getMinor();
}
