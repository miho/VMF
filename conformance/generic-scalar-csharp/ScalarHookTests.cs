using Eu.Mihosoft.Vmf.Conformance.Genericscalars;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ScalarHookTests
{
    [Fact]
    public void ClosedBehaviorUsesTheSameBoxedArgumentAsItsModelBase()
    {
        var model = HookedScalarReading.NewBuilder().WithSample(7).Build();
        ScalarHookChannel<int?> throughBase = model;
        Assert.Equal(1, model.HookRuns());
        Assert.Throws<ArgumentException>(() => throughBase.ReplaceBehavior(new WrongBehavior()));
        model.ReplaceBehavior(new ReadingOverride());
        Assert.Equal(12, model.HookRuns());
        Assert.Equal(7, throughBase.Sample);
        Assert.Same(model.BehaviorOverride, throughBase.BehaviorOverride);
    }

    [Theory]
    [InlineData(false, VmfDocumentFormat.Json)]
    [InlineData(false, VmfDocumentFormat.Xml)]
    [InlineData(false, VmfDocumentFormat.Yaml)]
    [InlineData(true, VmfDocumentFormat.Json)]
    [InlineData(true, VmfDocumentFormat.Xml)]
    [InlineData(true, VmfDocumentFormat.Yaml)]
    public void ClosedScalarHooksRunOnPeerSnapshots(bool references, VmfDocumentFormat format)
    {
        var number = HookedScalarReading.NewBuilder().WithSample(7).Build();
        var text = TextScalarReading.NewBuilder().WithSample("label").Build();
        var root = ScalarHookReadings.NewBuilder().WithNumber(number).WithText(text).Build();
        Verify(root);
        string document = (references, format) switch
        {
            (true, VmfDocumentFormat.Xml) => ModelSnapshot.WriteXml(root),
            (true, VmfDocumentFormat.Yaml) => ModelSnapshot.WriteYaml(root),
            (true, _) => ModelSnapshot.Write(root),
            (false, VmfDocumentFormat.Xml) => ModelSnapshot.WritePlainXml(root),
            (false, VmfDocumentFormat.Yaml) => ModelSnapshot.WritePlainYaml(root),
            _ => ModelSnapshot.WritePlain(root),
        };
        Verify(Read(document));
        if (Environment.GetEnvironmentVariable("VMF_CONFORMANCE_GENERIC_SCALARS") is { } directory)
        {
            string suffix = $"generic-hook-{references.ToString().ToLowerInvariant()}.{format.ToString().ToLowerInvariant()}";
            Verify(Read(File.ReadAllText(Path.Combine(directory, "java-" + suffix))));
            File.WriteAllText(Path.Combine(directory, "csharp-" + suffix), document);
        }

        ScalarHookReadings Read(string source) => (references, format) switch
        {
            (true, VmfDocumentFormat.Xml) => ModelSnapshot.ReadXml<ScalarHookReadings>(source),
            (true, VmfDocumentFormat.Yaml) => ModelSnapshot.ReadYaml<ScalarHookReadings>(source),
            (true, _) => ModelSnapshot.Read<ScalarHookReadings>(source),
            (false, VmfDocumentFormat.Xml) => ModelSnapshot.ReadPlainXml<ScalarHookReadings>(source),
            (false, VmfDocumentFormat.Yaml) => ModelSnapshot.ReadPlainYaml<ScalarHookReadings>(source),
            _ => ModelSnapshot.ReadPlain<ScalarHookReadings>(source),
        };
    }

    private static void Verify(ScalarHookReadings root)
    {
        var number = Assert.IsType<HookedScalarReading>(root.Number);
        ScalarHookChannel<int?> boxed = number;
        ForwardedScalarHookChannel<string?> forwarded = Assert.IsType<TextScalarReading>(root.Text);
        Assert.Equal(1, number.HookRuns());
        Assert.Equal(7, boxed.Sample);
        var view = boxed.AsReadOnly();
        Assert.Equal(7, view.Sample);
        Assert.Equal("label", forwarded.AsReadOnly().Sample);
        boxed.Sample = null;
        Assert.Null(view.Sample);
        boxed.Sample = 7;
    }

    private sealed class WrongBehavior : ScalarHookChannel<int?>.Behavior { }

    private sealed class ReadingOverride : HookedScalarReading.Behavior
    {
        public int HookRuns(HookedScalarReading caller) => 12;
    }
}
