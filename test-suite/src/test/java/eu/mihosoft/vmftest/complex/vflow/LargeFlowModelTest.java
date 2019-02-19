package eu.mihosoft.vmftest.complex.vflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import eu.mihosoft.vcollections.EventUtil;
import eu.mihosoft.vmf.runtime.core.Change;



public class LargeFlowModelTest {

    @Test
    public void createAndUndoTest() {

        VFlow flow = VFlow.newInstance();
        flow.vmf().changes().start();

        System.out.print("> creating nodes: ");
        workflowTest(flow, 8, 6);
        System.out.println("[done]");

        long numNodes = flow.vmf().content().stream(VNode.class).count();
        long numObjects = flow.vmf().content().stream().count();

        System.out.println("#nodes: " + numNodes);
        System.out.println("#objects: " + numObjects);

        assertThat("we expect a certain number of nodes", numNodes, equalTo(19681L));
        assertThat("we expect a certain number of objects", numObjects, equalTo(236161L));
        
        List<Change> changesToRevert = new ArrayList<>(flow.vmf().changes().all());
        Collections.reverse(changesToRevert);

        System.out.print("> undoing changes... ");

        // ... and undo all changes
        changesToRevert.stream().forEach(ch->{
            // System.out.println("-------- undo change: --------");
            // System.out.println(" -> obj: " + System.identityHashCode(ch.object()));
            // System.out.println(" -> obj type: " + ch.object().getClass().getName());
            // System.out.println(" -> obj name: " + 
            //   ch.object().vmf().reflect().propertyByName("name").
            //   map(p->p.get()==null?"<not set>":p.get()).orElse("<none>"));
            // System.out.println(" -> prop to undo: " + ch.propertyName());
            // // System.out.println(" -> change: " + ch.listChange().);    
            ch.undo();
        });

        System.out.println("[done]");

        // System.out.println("--");
        // flow.vmf().content().stream(VNode.class).forEach(obj->{
        //     System.out.println("obj: " + System.identityHashCode(obj) + " " + System.identityHashCode(obj.getParent()));});
        // System.out.println("--");

        numNodes = flow.vmf().content().stream(VNode.class).count();
        numObjects = flow.vmf().content().stream().count();

        System.out.println("#nodes: " + numNodes);
        System.out.println("#objects: " + numObjects);

        assertThat("after undo, we expect exactly one node", numNodes, equalTo(1L));
        assertThat("after undo, we expect exactly one object", numObjects, equalTo(1L));

        // N na = N.newInstance();

        // na.vmf().changes().start();

        // na.vmf().changes().addListener((change)-> {
        //     String message = "prop: "+change.propertyName() +"\n"
        //      +change.listChange().
        //     map(lc->EventUtil.toStringWithDetails(lc)).
        //     orElse(change.propertyChange().map(pc-> {
        //         String msg = "";
        //         msg += " -> old: " + pc.oldValue();
        //         msg += "\n -> new: " + pc.newValue();
        //         return msg;
        //     }).orElse("<no change>"));
        //     System.out.println(message);
        // });

        // C c = C.newInstance();

        // na.setC(c);

        // na.getC2().add(c);

        // System.out.println("prev: " + na.getC());

        // System.out.println("# changes: " + na.vmf().changes().all().size());

        // List<Change> changesToRevert = new ArrayList<>(na.vmf().changes().all());
        // Collections.reverse(changesToRevert);

        // // ... and undo all changes
        // changesToRevert.stream().forEach(ch->{
        //     System.out.println("-------- undo change: --------");ch.undo();}
        // );

    }

    public static void workflowTest(VFlow workflow, int depth, int width) {

        if (depth < 1) {
            return;
        }

        String[] connectionTypes = {"control", "data", "event"};

        for (int i = 0; i < width; i++) {

            VNode n;

            if (i % 2 == 0) {
                VFlow subFlow = workflow.newSubFlow(null);
                n = subFlow;
                workflowTest(subFlow, depth - 1, width);
            } else {
                n = workflow.newNode(null);
            }

            n.setName("Node id=" + n.getId());

//            n.getVisualizationRequest().set(VisualizationRequest.KEY_MAX_CONNECTOR_SIZE, 10.0);

            String type = connectionTypes[i % connectionTypes.length];

            Connector input1 = n.addInput(type);
            Connector input2 = n.addInput("event");

//            n.getVisualizationRequest().set(VisualizationRequest.KEY_DISABLE_EDITING, true);

            for (int j = 0; j < 3; j++) {
                n.addInput(type);
            }

//            n.addInput(type);
//            n.addInput(type);
            n.addOutput(type);
            n.addOutput("event");
            n.addOutput(type);

            for (int j = 0; j < 3; j++) {
                n.addOutput(type);
            }


            n.setWidth(300);
            n.setHeight(200);

            n.setX((i % 5) * (n.getWidth() + 30));
            n.setY((i / 5) * (n.getHeight() + 30));



        }
    }

}
