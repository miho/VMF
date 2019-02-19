package eu.mihosoft.vmftest.complex.vflow;

import java.util.Objects;

import eu.mihosoft.vmf.runtime.core.*;

public class VNodeDelegate implements DelegatedBehavior<VNode>{
    private VNode caller;

    public void setCaller(VNode caller) {
        this.caller = caller;
    }

    public Input addInput(String type) {
        Input input = Input.newBuilder().withType(type).build();
        caller.getInputs().add(input);
        return input;
    }
    public Output addOutput(String type) {
        Output outputs = Output.newBuilder().withType(type).build();
        caller.getOutputs().add(outputs);
        return outputs;
    }
}
