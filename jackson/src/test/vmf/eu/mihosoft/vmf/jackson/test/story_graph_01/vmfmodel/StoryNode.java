package eu.mihosoft.vmf.jackson.test.story_graph_01.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Doc;
import eu.mihosoft.vmf.core.InterfaceOnly;

import javax.management.Descriptor;

@InterfaceOnly
interface WithId {
    @Doc
    String getId();
}

@InterfaceOnly
interface WithName {
    String getName();
}

@InterfaceOnly
interface WithDescription {
    String getDescription();
}

@InterfaceOnly
interface WithText {
    String getText();
}

@InterfaceOnly
interface WithResources {
    @Contains
    Resource[] getResources();
}

@InterfaceOnly
interface WithChoice {
    @Contains
    Choice getChoice();
}

@InterfaceOnly
interface WithMedia {
    String getMedia();
}

@InterfaceOnly
interface WithEvents {
    @Contains
    Event[] getEvents();
}

@InterfaceOnly
interface WithTransitions {
    @Contains
    String[] getTransitions();
}

@InterfaceOnly
interface WithOnEntryUpdates {
    @Contains
    Update[] getOnEntryUpdates();
}

@InterfaceOnly
interface WithOnEntryConditions {
    @Contains
    Condition[] getOnEntryConditions();
}

@InterfaceOnly
interface WithNodes {
    @Contains
    StoryNode[] getNodes();
}

@Doc
interface StoryNode extends WithId, WithName, WithDescription, WithText, WithResources, WithEvents, WithTransitions,
        WithOnEntryUpdates, WithOnEntryConditions, WithChoice {
    @Contains
    State getState();
    @Contains
    StoryNode[] getNodes();

    String getId();
    String getName();
    String getDescription();

    String getText();

    @Contains
    Resource[] getResources();

    @Contains
    Update[] getOnEntryUpdates();

    @Contains
    Condition[] getOnEntryConditions();

    @Contains
    Choice getChoice();

    @Contains
    Event[] getEvents();

    String[] getTransitions();
}


interface Resource extends WithId, WithName, WithDescription, WithText, WithMedia {
    String getId();
    String getName();
    String getDescription();

    String getText();
    String getMedia();
}


interface Event extends WithId, WithName, WithDescription, WithText, WithResources {
    String getId();
    String getName();
    String getDescription();

    String getText();
    @Contains
    Resource[] getResources();
}

interface Choice extends WithText {
    String getText();
}


// TODO options about extending types not fully working in schema
interface Update {

}

interface StateUpdate extends Update {
    String getKey();
    String getValueUpdate();
}


// TODO options about extending types not fully working in schema
interface Condition {

}

interface StateCondition extends Condition {
    String getKey();
    String getValue();
    String getOperator();
}


//@DelegateTo(className = "StateBehavior")
interface State {
//    <T> T fromStateByKey(String key);
//
//    void toStateByKey(String key, Object value);
//
//    void removeStateByKey(String key);
//
//    void clearState();
//
//    boolean containsByKey(String key);
}