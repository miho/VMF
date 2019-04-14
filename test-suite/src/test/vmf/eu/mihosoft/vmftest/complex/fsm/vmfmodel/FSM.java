package eu.mihosoft.vmftest.complex.fsm.vmfmodel;

import eu.mihosoft.vmf.core.*;

@Doc("This model entity is a finite state machine.")
interface FSM {

    String getName();

    State getInitialState();
    State getCurrentState();
    State[] getFinalState();

    @Contains(opposite="owningFSM")
    State[] getOwnedState();

}

interface State {

    String getName();

    @Container(opposite = "ownedState")
    FSM getOwningFSM();

    @Contains(opposite="source")
    Transition[] getOutgoingTransitions();
    @Contains(opposite="target")
    Transition[] getIncomingTransitions();

}

interface Transition {

    String getInput();
    String getOutput();

    @Container(opposite="outgoingTransitions")
    State getSource();
    @Container(opposite="incomingTransitions")
    State getTarget();

    Action[] getActions();
}

interface Action {
    String getName();
}