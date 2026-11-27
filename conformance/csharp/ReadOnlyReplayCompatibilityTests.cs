using Eu.Mihosoft.Vmf.Conformance.Redeclarations;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ReadOnlyReplayCompatibilityTests
{
    [Fact]
    public void AForeignMarkerlessImmutableValueRemainsAModelPayload()
    {
        var source = new PayloadRecord();
        var target = new PayloadRecord();
        var view = source.AsReadOnly();
        var history = view.VmfChanges;

        // An external value may provide a similar-looking author API.
        var host = new ExternalHost();
        history.Start();
        source.Payload = host;
        Assert.Single(history.History).Apply(target);
        Assert.Same(host, target.Payload);
        history.Stop();

        // This has the public contract of a value emitted before IImmutableModel.
        var value = new ForeignBadge();
        history.Start();
        source.Payload = value;
        var retained = Assert.Single(history.History);
        source.Payload = null;
        history.Clear();
        history.Stop();

        Assert.Same(view, retained.Source);
        Assert.Same(value, retained.NewValue);
        Assert.Throws<ArgumentException>(() => retained.Apply(target));
        Assert.Same(host, target.Payload);
    }

    [Fact]
    public void CapturedValuesAndPositionsStayApplicableAfterTheSourceAndHistoryChange()
    {
        var source = new CollectionProfile();
        var target = new CollectionProfile();
        target.Messages.Add("existing");
        var view = source.AsReadOnly();
        var history = view.VmfChanges;
        history.Start();
        source.Messages.Add("captured");
        source.Options.Put("occupied", null);
        var list = history.History[0];
        var map = history.History[1];

        source.Messages[0] = "later";
        source.Options.Put("occupied", true);
        history.Clear();
        history.Stop();

        Assert.Same(view, list.Source);
        Assert.Same(view, map.Source);
        Assert.Empty(history.History);
        list.Apply(target);
        map.Apply(target);
        Assert.Equal(["captured", "existing"], target.Messages);
        Assert.True(target.Options.ContainsKey("occupied"));
        Assert.Null(target.Options["occupied"]);
        Assert.Equal(["later"], source.Messages);
        Assert.True(source.Options["occupied"]);
    }

    private class LegacyValue
    {
        public ReadOnlyModelChanges VmfChanges => throw new InvalidOperationException("Metadata must not invoke the getter.");
        public LegacyValue AsReadOnly() => throw new InvalidOperationException("Metadata must not call the projection.");
        public static Builder NewBuilder() => throw new InvalidOperationException("Metadata must not invoke a factory.");

        public sealed class Builder : IModelBuilder<LegacyValue>
        {
            public LegacyValue Build() => throw new InvalidOperationException("Metadata must not construct a model.");
        }
    }

    private sealed class ForeignBadge : LegacyValue { }

    private sealed class ExternalHost
    {
        public ReadOnlyModelChanges VmfChanges => throw new InvalidOperationException("Metadata must not invoke the getter.");
        public ExternalHost AsReadOnly() => throw new InvalidOperationException("Metadata must not call the projection.");
    }
}
