package eu.mihosoft.vmftest.complex.vflow;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public class ConnectionDelegate implements DelegatedBehavior<Connection> {
    private Connection caller;

    private boolean changePermitted = false;

    @Override
    public void setCaller(Connection caller) {
        this.caller = caller;
    }

    public void onConnectionInstantiated() {
        // caller.vmf().reflect().propertyByName("id").
        // ifPresent(p->p.addChangeListener(change -> {         
        //     if(!changePermitted) {
        //         throw new RuntimeException(
        //             "property 'id' is synced, cannot set it manually.");
        //     }
        // }));

        caller.vmf().reflect().propertyByName("sender").
        ifPresent(p->p.addChangeListener(change -> {       
            
            String senderId = "<none>";
            if(caller.getSender()!=null   
              && caller.getSender().getId()!=null) senderId = caller.getSender().getId();
            String receiverId = "<none>";
            if(caller.getReceiver()!=null   
              && caller.getReceiver().getId()!=null) receiverId = caller.getReceiver().getId();
            
            String id = senderId + " -> " + receiverId;

            changePermitted = true;
            caller.setId(id);
            changePermitted = false;
        }));

        caller.vmf().reflect().propertyByName("receiver").
        ifPresent(p->p.addChangeListener(change -> {  
            
            String senderId = "<none>";
            if(caller.getSender()!=null   
              && caller.getSender().getId()!=null) senderId = caller.getSender().getId();
            String receiverId = "<none>";
            if(caller.getReceiver()!=null   
              && caller.getReceiver().getId()!=null) receiverId = caller.getReceiver().getId();
            
            String id = senderId + " -> " + receiverId;

            changePermitted = true;
            caller.setId(id);
            changePermitted = false;
        }));
    }
}