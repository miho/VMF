/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
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

        int numTransitions = 100_00;

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