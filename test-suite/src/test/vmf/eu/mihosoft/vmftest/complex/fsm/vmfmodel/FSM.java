package eu.mihosoft.vmftest.complex.fsm.vmfmodel;

import eu.mihosoft.vmf.core.*;

@Doc("This model entity is a finite state machine.")
interface FSM {

    String getName();

    State getInitialState();
    State getCurrentState();
    State getFinalState();

    @Contains(opposite="owningFSM")
    State[] getOwnedState();

}

interface State {

    String getName();

    @Container(opposite = "ownedState")
    FSM getOwningFSM();

    @Contains(opposite="source")
    Transition getOutgoingTransition();
    @Contains(opposite="target")
    Transition getIncomingTransition();

}

interface Transition {

    String getInput();
    String getOutput();

    @Container(opposite="outgoingTransition")
    State getSource();
    @Container(opposite="incomingTransition")
    State getTarget();

    Action getAction();
}

interface Action {
    String getName();
}