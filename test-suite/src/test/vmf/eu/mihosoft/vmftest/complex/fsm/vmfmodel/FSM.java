package eu.mihosoft.vmftest.complex.fsm.vmfmodel;

import eu.mihosoft.vmf.core.*;
import eu.mihosoft.vmf.core.VMFEquals;

@Doc("This model entity is a finite state machine.")
@VMFEquals
interface FSM {

    String getName();

    State getInitialState();
    State getCurrentState();
    State[] getFinalState();

    @Contains(opposite="owningFSM")
    State[] getOwnedState();

}

@VMFEquals
interface State {

    String getName();

    @Container(opposite = "ownedState")
    FSM getOwningFSM();

    @Contains(opposite="source")
    Transition[] getOutgoingTransitions();
    @Contains(opposite="target")
    Transition[] getIncomingTransitions();

}

@VMFEquals
interface Transition {

    String getInput();
    String getOutput();

    @Container(opposite="outgoingTransitions")
    State getSource();
    @Container(opposite="incomingTransitions")
    State getTarget();

    Action[] getActions();
}

@VMFEquals
interface Action {
    String getName();
}