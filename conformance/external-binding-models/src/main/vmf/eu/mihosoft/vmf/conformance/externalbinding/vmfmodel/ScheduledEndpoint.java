package eu.mihosoft.vmf.conformance.externalbinding.vmfmodel;

interface EndpointHeader { String getId(); }

interface ScheduledEndpoint extends EndpointHeader, ForwardModeSamples<State> {}
