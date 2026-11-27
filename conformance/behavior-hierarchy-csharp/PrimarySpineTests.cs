using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behaviorhierarchy;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class PrimarySpineTests
{
    [Fact]
    public void PrimaryBehaviorWithDataSecondariesRetainsTheConcreteCallerAndSnapshotContract()
    {
        SpineNode.BindingCount = 0;
        SpineConnector.BindingCount = 0;
        SpineEndpoint.BindingCount = 0;
        SpineFlow.FlowBindingCount = 0;
        var flow = new SpineFlow();
        var endpoint = new SpineEndpoint { Name = "route" };
        Assert.Equal(1, SpineFlow.FlowBindingCount);
        Assert.Null(SpineFlow.ChannelAtHook);
        Assert.Equal("default", flow.Channel);
        Assert.Equal(0, SpineNode.BindingCount);
        Assert.Equal(1, SpineEndpoint.BindingCount);

        var node = flow.NewNode("nested")!;
        SpineNode asNode = flow;
        var first = asNode.AddPort("number")!;
        var other = node.AddPort("number")!;
        Assert.Same(flow, first.Owner);
        Assert.Same(node, other.Owner);
        Assert.Equal(1, asNode.PortsCreated());
        Assert.Equal(1, node.PortsCreated());
        Assert.Equal(2, SpineNode.BindingCount);
        Assert.Equal(2, SpineConnector.BindingCount);
        SpineConnector connector = first;
        Assert.True(flow.Connect(connector, other));
        Assert.False(flow.Connect(flow, node, "number"));
        Assert.Equal(1, connector.ConnectionsTried());
        Assert.Equal(0, other.ConnectionsTried());
        Assert.Equal(2, SpineConnector.BindingCount);

        flow.Endpoint = endpoint;
        flow.Featured = other;
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR_HIERARCHY");
        foreach (bool references in new[] { true, false })
        {
            string suffix = references ? "-primary-spine-reference.json" : "-primary-spine-by-value.json";
            string wire = references ? ModelSnapshot.Write(flow) : ModelSnapshot.WriteWithoutReferences(flow, global::Vmf.Runtime.ReferenceMode.Omit);
            if (exchange is not null)
            {
                var java = File.ReadAllText(Path.Combine(exchange, "java" + suffix));
                Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(wire)));
                File.WriteAllText(Path.Combine(exchange, "csharp" + suffix), wire);
                wire = java;
            }

            var restored = references ? ModelSnapshot.Read<SpineFlow>(wire) : ModelSnapshot.ReadWithoutReferences<SpineFlow>(wire);
            var restoredNode = restored.Nodes[0]!;
            var restoredPort = restoredNode.Ports[0]!;
            Assert.Same(restoredNode, restoredPort.Owner);
            Assert.Equal("route", restored.Endpoint?.Name);
            Assert.Equal(0, restored.PortsCreated());
            Assert.Equal(0, restoredPort.ConnectionsTried());
            Assert.True(restored.Connect(restored.Ports[0], restoredPort));
            if (references) Assert.Same(restoredPort, restored.Featured);
            else Assert.Null(restored.Featured);
        }

        string plain = ModelSnapshot.WritePlain(endpoint);
        if (exchange is not null)
        {
            var java = File.ReadAllText(Path.Combine(exchange, "java-primary-spine-plain.json"));
            Assert.True(JsonNode.DeepEquals(JsonNode.Parse(java), JsonNode.Parse(plain)));
            File.WriteAllText(Path.Combine(exchange, "csharp-primary-spine-plain.json"), plain);
            plain = java;
        }
        var data = JsonNode.Parse(plain)!;
        Assert.Null(data["@vmf-type"]);
        Assert.Null(data["@vmf-id"]);
        var plainEndpoint = ModelSnapshot.ReadPlain<SpineEndpoint>(plain);
        Assert.Equal("route", plainEndpoint.Name);
        Assert.Equal("default", plainEndpoint.Channel);
    }
}
