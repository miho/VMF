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