package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

import java.util.List;

@InterfaceOnly
interface NamedItem {
    @GetterOnly String getLabel();
}

interface AssetData {
    String getCaption();
}

interface TaskItem extends AssetData, NamedItem {
    String getTaskId();
}

interface NoteItem extends NamedItem {
    String getMessage();
}

interface Folder {
    @Contains NamedItem[] getItems();
    @Contains NamedItem getFirst();
    NamedItem getSelected();
}

@InterfaceOnly
interface GenericFolder<T extends NamedItem> {
    @Contains List<T> getEntries();
    @Contains T getHead();
    T getActive();
}

interface TaskFolder extends GenericFolder<TaskItem> { }
