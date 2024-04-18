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
package eu.mihosoft.vmftest.complex.vflow.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface WithLocation {
    @DefaultValue(value="0")
    Integer getX();
    @DefaultValue(value="0")
    Integer getY();
}

@InterfaceOnly
interface WithDimensions {
    @DefaultValue(value="0")
    Integer getWidth();
    @DefaultValue(value="0")
    Integer getHeight();
}

@InterfaceOnly
interface WithId {
    String getId();
}

@InterfaceOnly
interface WithName {
    String getName();
}

@InterfaceOnly
interface WithType {
    @DefaultValue(value="\"default\"")
    String getType();
}

@InterfaceOnly
interface WithValue {
    Object getValue();
}


@InterfaceOnly
@DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.ConnectorDelegate")
interface Connector extends WithId, WithType, WithValue {
    @GetterOnly
    VNode getParent();

    @GetterOnly
    Connection[] getConnections();

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.ConnectorDelegate")
    ConnectionResult tryConnect(Connector c);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.ConnectorDelegate")
    ConnectionResult connect(Connector c);

}

interface Input extends Connector {
    @Container(opposite="inputs")
    VNode getParent();

    @Contains(opposite="receiver")
    Connection[] getConnections();
}

interface Output extends Connector {
    @Container(opposite="outputs")
    VNode getParent();

    @Contains(opposite="sender")
    Connection[] getConnections();
}

@DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.ConnectionDelegate")
interface Connection extends WithId, WithType {
    @Container(opposite="connections")
    Output getSender();

    @Container(opposite="connections")
    Input getReceiver();

    @Container(opposite="connections")
    VFlow getFlow();
}

interface ConnectionResult {
    Connection getConnection();
    boolean isSuccessful();
    @DefaultValue(value="\"\"")
    String getMessage();
}

interface VNode extends WithLocation, WithDimensions, WithId, WithType, WithValue, WithName {
    @Contains(opposite="parent")
    Input[] getInputs();

    @Contains(opposite="parent")
    Output[] getOutputs();

    @Container(opposite="nodes")
    VFlow getParent();

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VNodeDelegate")
    Input addInput( String type);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VNodeDelegate")
    Output addOutput(String type);
}

@DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
interface VFlow extends VNode {

    @Contains(opposite="parent")
    VNode[] getNodes();

    @Contains(opposite="flow")
    Connection[] getConnections();

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    ConnectionResult connect(Connector c1, Connector c2);
    
    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    ConnectionResult tryConnect(Connector c1, Connector c2);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    ConnectionResult connect(VNode n1, VNode n2, String type);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    VNode newNode(Object o);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    VFlow newSubFlow(Object o);

}