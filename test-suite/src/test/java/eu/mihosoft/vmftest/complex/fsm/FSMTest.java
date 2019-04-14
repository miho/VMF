package eu.mihosoft.vmftest.complex.fsm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

public class FSMTest {


    @Test
    public void fsmCreateAndUndoTest() {
        FSM fsm = null;

        long startCreation = System.nanoTime();

        int numMeasurements = 1;

        int numTransitions = 100_000;

        for(int j = 0; j < numMeasurements; j++) {
            fsm = FSM.newInstance();

            for(int i = 0; i < numTransitions; i++) {
                State s = State.newInstance();
                s.setName("State " + i);
                fsm.getOwnedState().add(s);

                if(i > 0) {
                    Transition transition = Transition.newInstance();

                    // Action a = Action.newBuilder().withName("action").build();
                    Action a = Action.newInstance();
                    a.setName("action");
                    transition.getActions().add(a);

                    State sender = fsm.getOwnedState().get(i-1);
                    State receiver = s;

                    transition.setInput(sender.getName());
                    transition.setOutput(receiver.getName());
                    
                    sender.getOutgoingTransitions().add(transition);
                    receiver.getIncomingTransitions().add(transition);
                }
            }

            fsm.setInitialState(fsm.getOwnedState().get(0));
            fsm.getFinalState().add(fsm.getOwnedState().get(fsm.getOwnedState().size()-1));
        }

        long stopCreation = System.nanoTime();

        System.out.println("creation took " 
          + ((stopCreation-startCreation)*1e-6/numMeasurements) + "ms");

        FSM clone = null;

        long startCloning = System.nanoTime();

        for(int j = 0; j < numMeasurements; j++) {
           clone = fsm.clone();
        }

        long stopCloning = System.nanoTime();

        System.out.println("cloning took "
         + ((stopCloning-startCloning)*1e-6/numMeasurements) + "ms");

        int numCreatedTransition = fsm.getOwnedState().size(); 
        int numCreatedTransitionClone = clone.getOwnedState().size(); 

        assertThat("We expect the number of transitions to be equal to the clone", numCreatedTransition, equalTo(numCreatedTransitionClone));
        assertThat("We expect an exact number of transitions", numCreatedTransition, equalTo(numTransitions));
        
        System.out.println("#states: " + clone.getOwnedState().size());
        
        System.out.println("equals: " + fsm.equals(clone));
        
        assertThat("We expect the clone to be equal to the original fsm", fsm, equalTo(clone));
        
        System.out.println("cmp-to-string: " + clone.toString().equals(fsm.toString()));
        
        assertThat("We expect the clone's toString() to be equal to the original fsm's toString()", 
                   clone.toString(), equalTo(fsm.toString())
        );
    }
}