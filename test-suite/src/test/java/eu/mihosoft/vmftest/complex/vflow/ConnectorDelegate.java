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

import java.util.Objects;
import java.util.Collection;
import java.util.List;

import eu.mihosoft.vcollections.EventUtil;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.*;
import vjavax.observer.collection.CollectionChange;
import vjavax.observer.collection.CollectionChangeListener;

public class ConnectorDelegate implements DelegatedBehavior<Connector> {
    private Connector caller;

    public void setCaller(Connector caller) {
        this.caller = caller;
    }

    public void onConnectorInstantiated() {
        // prevent duplicates & set id
        caller.getConnections().addChangeListener(
            (CollectionChangeListener<Connection, Collection<Connection>, CollectionChange<Connection>>) evt -> {
                for (Connection cnn : evt.added().elements()) {
                    // if(caller.getNodes().stream().filter(cnn2->Objects.equals(cnn,cnn2)).count()>1) {
                    if (caller.getConnections().stream().filter(cnn2 -> cnn == cnn2).count() > 1) {
                        throw new RuntimeException("Duplicate connections added: " + cnn);
                    }
                }
        });
    }

    static class ConnectorTuple {
        Input input;
        Output output;

        ConnectorTuple(Input input, Output output) {
            this.input = input;
            this.output = output;
        }
    }

    private ConnectorTuple sort(Connector c1, Connector c2) {

        Input input = null;
        Output output = null;

        if(c1 instanceof Input && c2 instanceof Output) {
            input = (Input) c1;
            output = (Output) c2;
        } else if(c1 instanceof Output && c2 instanceof Input) {
            input = (Input) c2;
            output = (Output) c1;
        }

        return new ConnectorTuple(input, output);
    }

    public ConnectionResult connect(Connector c2) {

        Connector c1 = caller;

        ConnectionResult result = tryConnect(c2);

        if(!result.isSuccessful()) {
            return result;
        }

        ConnectorTuple connectors = sort(c1, c2);

        Input input = connectors.input;
        Output output = connectors.output;

        String connectionType = input.getType();
        Connection connection = Connection.newBuilder().withType(connectionType).build();
        
        connection.setSender(output);
        connection.setReceiver(input);

        input.getParent().getParent().getConnections().add(connection);

        return result;
    }

    public ConnectionResult tryConnect(Connector c2) {
        
        Connector c1 = caller;

        ConnectionResult result = ConnectionResult.newInstance();
        result.setSuccessful(true);

        if(c1==null || c2 == null) {
            result.setSuccessful(false);
            result.setMessage("cannot establish connection between 'null' connectors");
            return result;
        }

        if(c1.getParent()==null || c2.getParent() == null) {
            result.setSuccessful(false);
            result.setMessage("cannot establish connection between connectors without parent node");
            return result;
        }

        if(c1.getParent()==null || c2.getParent() == null) {
            result.setSuccessful(false);
            result.setMessage("cannot establish connection between connectors without parent node");
            return result;
        }

        if(c1.getParent().getParent()==null || c2.getParent().getParent() == null) {
            result.setSuccessful(false);
            result.setMessage("cannot establish connection between nodes that don't belong to a flow object");
            return result;
        }

        result.setSuccessful(true);

        Input input = null;
        Output output = null;

        ConnectorTuple connectors = sort(c1, c2);
        input = connectors.input;
        output = connectors.output;

        if(input == null || output==null) {
            result.setSuccessful(false);
            result.setMessage("cannot establish a connection between two outputs or two inputs");
            return result;
        }

        if(result.isSuccessful() && !Objects.equals(c1.getType(), c2.getType())) {
            result.setSuccessful(false);
            result.setMessage("cannot establish a connection between connectors of incompatible types "
            + "[ input-type: " + input.getType() + ", output-type: " + output.getType() + "]");
            return result;
        }

        return result;
    }

}
