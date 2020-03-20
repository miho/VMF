package eu.mihosoft.vmftest.events_undo_redo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class UndoRedoWithContainmentTest {
    @Test
    public void unoRedoWithListContainmentTestViaListAdd() {

        ParentListContainment parent = ParentListContainment.newInstance();

        // count total change events
        AtomicInteger numChanges= new AtomicInteger(0);
        parent.vmf().changes().addListener(change -> {
            System.out.println("change: prop="+change.propertyName());
            numChanges.getAndIncrement();
        });

        // start the change recording
        parent.vmf().changes().start();

        // create child
        ChildListContainment child = ChildListContainment.newInstance();

        // count changes received by 'parent' properties
        AtomicInteger numChangesProp = new AtomicInteger(0);
        child.vmf().reflect().propertyByName("parent").ifPresent(p -> {
            p.addChangeListener(change -> numChangesProp.getAndIncrement());
        });

        // add the child to the containment collection which sets the parent
        parent.getChildren().add(child);

        assertThat("there's exactly one property 'parent' change", numChanges.get(), equalTo(1));
        assertThat("there's exactly one undoable change", parent.vmf().changes().all().size(), equalTo(1));
        assertThat("there is one change in total (second is fired only locally in child)", numChanges.get(), equalTo(1));

        // set a child property and see if changes are recorded in parent
        child.setName("my new name");

        assertThat("there are exactly two undoable change in the list", parent.vmf().changes().all().size(), equalTo(2));
    }

    @Test
    public void unoRedoWithListContainmentTestViaSetParent() {

        ParentListContainment parent = ParentListContainment.newInstance();

        // count total change events
        AtomicInteger numChanges= new AtomicInteger(0);
        parent.vmf().changes().addListener(change -> {
            System.out.println("change: prop="+change.propertyName());
            numChanges.getAndIncrement();
        });

        // start the change recording
        parent.vmf().changes().start();

        // create child
        ChildListContainment child = ChildListContainment.newInstance();

        // count changes received by 'parent' properties
        AtomicInteger numChangesProp = new AtomicInteger(0);
        child.vmf().reflect().propertyByName("parent").ifPresent(p -> {
            p.addChangeListener(change -> numChangesProp.getAndIncrement());
        });

        // set the parent which adds the child to the containment collection
        child.setParent(parent);

        assertThat("there's exactly one property 'parent' change", numChangesProp.get(), equalTo(1));
        assertThat("there's exactly one undoable change", parent.vmf().changes().all().size(), equalTo(1));
        assertThat("there is one changes in total (second is only fired locally in child)", numChanges.get(), equalTo(1));

        // set a child property and see if changes are recorded in parent
        child.setName("my new name");

        assertThat("there are exactly two undoable changes in the list", parent.vmf().changes().all().size(), equalTo(2));
    }

    @Test
    public void unoRedoWithSingleContainmentTest1() {

        ParentSingleContainment parent = ParentSingleContainment.newInstance();

        // count total change events
        AtomicInteger numChanges= new AtomicInteger(0);
        parent.vmf().changes().addListener(change -> {
            System.out.println("change: prop="+change.propertyName());
            numChanges.getAndIncrement();
        });

        // start the change recording
        parent.vmf().changes().start();

        // create child
        ChildSingleContainment child = ChildSingleContainment.newInstance();

        // count changes received by 'parent' properties
        AtomicInteger numChangesProp = new AtomicInteger(0);
        child.vmf().reflect().propertyByName("parent").ifPresent(p -> {
            p.addChangeListener(change -> numChangesProp.getAndIncrement());
        });

        // set child which sets the opposite as well
        parent.setChild(child);

        assertThat("there's exactly one property 'parent' change", numChangesProp.get(), equalTo(1));
        assertThat("there's exactly one undoable change", parent.vmf().changes().all().size(), equalTo(1));
        assertThat("there is one change in total (second is only fired locally in child)", numChanges.get(), equalTo(1));

        // set a child property and see if changes are recorded in parent
        child.setName("my new name");

        assertThat("there are exactly two undoable changes in the list", parent.vmf().changes().all().size(), equalTo(2));
    }

    @Test
    public void unoRedoWithSingleContainmentTest2() {

        ParentSingleContainment parent = ParentSingleContainment.newInstance();

        // count total change events
        AtomicInteger numChanges= new AtomicInteger(0);
        parent.vmf().changes().addListener(change -> {
            System.out.println("change: prop="+change.propertyName());
            numChanges.getAndIncrement();
        });

        // start the change recording
        parent.vmf().changes().start();

        // create child
        ChildSingleContainment child = ChildSingleContainment.newInstance();

        // count changes received by 'parent' properties
        AtomicInteger numChangesProp = new AtomicInteger(0);
        child.vmf().reflect().propertyByName("parent").ifPresent(p -> {
            p.addChangeListener(change -> numChangesProp.getAndIncrement());
        });

        // set the parent which sets the opposite as well
        child.setParent(parent);

        assertThat("there's exactly one property 'parent' change", numChangesProp.get(), equalTo(1));
        assertThat("there's exactly one undoable change", parent.vmf().changes().all().size(), equalTo(1));
        assertThat("there is one change in total (second is fired only locally in child)", numChanges.get(), equalTo(1));

        // set a child property and see if changes are recorded in parent
        child.setName("my new name");

        assertThat("there are exactly two undoable changes in the list", parent.vmf().changes().all().size(), equalTo(2));
    }
    @Test
    public void unoRedoWithSingleContainmentTestwithadditionalListener() {
        ParentSingleContainment parent = ParentSingleContainment.newInstance();

        // register non-recursive listener to reproduce issue #30
        // see https://github.com/miho/VMF/issues/30
        parent.vmf().changes().addListener(change -> {
            System.out.println("change: prop="+change.propertyName());
        }, false);

        // start the change recording
        parent.vmf().changes().start();

        // create child
        ChildSingleContainment child = ChildSingleContainment.newInstance();

        // set the parent which sets the opposite as well
        child.setParent(parent);

        assertThat("there's exactly one undoable change", parent.vmf().changes().all().size(), equalTo(1));

        // set a child property and see if changes are recorded in parent
        child.setName("my new name");

        assertThat("there are exactly two undoable changes in the list", parent.vmf().changes().all().size(), equalTo(2));
    }





}
