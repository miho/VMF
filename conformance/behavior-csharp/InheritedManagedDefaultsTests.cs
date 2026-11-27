using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behavior;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed partial class InheritedManagedDefaultsTests
{
    [Fact]
    public void ConstructorAndBuilderApplyInheritedDefaultsAndKeepHookObservers()
    {
        InheritedManagedDefaultsState.Reset();
        Assert.NotNull(ModelSnapshot.TypeCatalog.Find(typeof(InheritedManagedValues))?.FindProperty("numbers"));
        Assert.Empty(InheritedManagedDefaultsState.Phases);

        var direct = new InheritedManagedValues();
        AssertState(direct, "values", false, [1], "saved-1", 1, ["[99]>[1]"], ["seed-a>", "seed-b>", ">saved-1"]);

        InheritedManagedDefaultsState.Reset();
        var builder = InheritedManagedValues.NewBuilder();
        Assert.Equal(["numbers:1", "settings:1"], InheritedManagedDefaultsState.Phases);
        Assert.Null(InheritedManagedDefaultsState.LastOwner);
        AssertStaged(builder.Build(), "values");

        InheritedManagedDefaultsState.Reset();
        var references = ModelSnapshot.Read<InheritedManagedReferences>(
            """{"picked":"child-1","child":{"@vmf-id":"child-1","label":"child"}}""");
        AssertStaged(references, "references");
        Assert.Same(references.Child, references.Selection);

        InheritedManagedDefaultsState.Reset();
        var cleared = ModelSnapshot.ReadPlain<InheritedManagedValues>("""{"counts":[],"settings":{}}""");
        AssertStaged(cleared, "values", cleared: true);
    }

    [Theory]
    [InlineData(false, VmfDocumentFormat.Json)]
    [InlineData(false, VmfDocumentFormat.Xml)]
    [InlineData(false, VmfDocumentFormat.Yaml)]
    [InlineData(true, VmfDocumentFormat.Json)]
    [InlineData(true, VmfDocumentFormat.Xml)]
    [InlineData(true, VmfDocumentFormat.Yaml)]
    public void ExistingSnapshotProfilesStageInheritedDefaultsOnceAndPreserveAliases(bool references, VmfDocumentFormat format)
    {
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR");
        string profile = references ? "refs" : "plain";
        string extension = format.ToString().ToLowerInvariant();
        var child = new InheritedManagedChild { Label = "child" };
        InheritedManagedDefaultsState.Reset();
        var value = InheritedManagedReferences.NewBuilder().WithNumbers([27]).WithSettings([new("wire", 31)])
            .WithChild(child).Build();
        if (references) value.Selection = child;
        AssertStaged(value, "references", explicitValues: true);
        string explicitDocument = SnapshotCollectionBehaviorTests.Write(value, references, format);
        string omittedDocument = SnapshotCollectionBehaviorTests.Omission(format, nameof(InheritedManagedValues));
        if (format == VmfDocumentFormat.Json)
        {
            var json = JsonNode.Parse(explicitDocument)!;
            Assert.Equal(27, json["counts"]![0]!.GetValue<int>());
            if (references) Assert.True(json.AsObject().ContainsKey("picked"));
        }
        VerifyDocuments(omittedDocument, explicitDocument);
        if (directory is null) return;

        string Name(string author, string state) =>
            Path.Combine(directory, $"{author}-inherited-managed-{profile}-{state}.{extension}");
        File.WriteAllText(Name("csharp", "omitted"), omittedDocument);
        File.WriteAllText(Name("csharp", "explicit"), explicitDocument);
        VerifyDocuments(File.ReadAllText(Name("java", "omitted")), File.ReadAllText(Name("java", "explicit")));

        void VerifyDocuments(string omitted, string supplied)
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                VerifyProfile(typedValues: false);
                if (!references) VerifyProfile(typedValues: true);
            }

            void VerifyProfile(bool typedValues)
            {
                InheritedManagedDefaultsState.Reset();
                var restored = SnapshotCollectionBehaviorTests.Read<InheritedManagedValues>(omitted, references, format, typedValues);
                AssertStaged(restored, "values");
                InheritedManagedDefaultsState.Reset();
                var restoredReferences = SnapshotCollectionBehaviorTests.Read<InheritedManagedReferences>(
                    supplied, references, format, typedValues);
                AssertStaged(restoredReferences, "references", explicitValues: true);
                Assert.Equal("child", restoredReferences.Child?.Label);
                if (references) Assert.Same(restoredReferences.Child, restoredReferences.Selection);
                else Assert.Null(restoredReferences.Selection);
            }
        }
    }

    [Fact]
    public void DerivedConstructorAndBuilderUseOnlyTheSelectedHook()
    {
        InheritedManagedDefaultsState.Reset();
        Assert.NotNull(ModelSnapshot.TypeCatalog.Find(typeof(InheritedManagedOverridden))?.FindProperty("numbers"));
        Assert.Empty(InheritedManagedDefaultsState.Phases);

        AssertDirect(new InheritedManagedDerived(), "references");
        InheritedManagedDefaultsState.Reset();
        AssertDirect(new InheritedManagedOverridden(), "replacement");

        InheritedManagedDefaultsState.Reset();
        var builder = InheritedManagedDerived.NewBuilder();
        Assert.Equal(["numbers:1", "settings:1"], InheritedManagedDefaultsState.Phases);
        Assert.Null(InheritedManagedDefaultsState.LastOwner);
        AssertStaged(builder.Build(), "references");

        InheritedManagedDefaultsState.Reset();
        var cleared = InheritedManagedOverridden.NewBuilder().WithNumbers([]).WithSettings([]).Build();
        AssertStaged(cleared, "replacement", cleared: true);

        static void AssertDirect(InheritedManagedFields owner, string kind) =>
            AssertState(owner, kind, false, [1], "saved-1", 1, ["[99]>[1]"], ["seed-a>", "seed-b>", ">saved-1"]);
    }

    [Theory]
    [InlineData(false, VmfDocumentFormat.Json)]
    [InlineData(false, VmfDocumentFormat.Xml)]
    [InlineData(false, VmfDocumentFormat.Yaml)]
    [InlineData(true, VmfDocumentFormat.Json)]
    [InlineData(true, VmfDocumentFormat.Xml)]
    [InlineData(true, VmfDocumentFormat.Yaml)]
    public void DerivedSnapshotPreservesStagingSelectedHooksAndInheritedAliases(bool references, VmfDocumentFormat format)
    {
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR");
        string profile = references ? "refs" : "plain";
        string extension = format.ToString().ToLowerInvariant();
        var child = new InheritedManagedChild { Label = "child" };
        InheritedManagedDefaultsState.Reset();
        var value = InheritedManagedOverridden.NewBuilder().WithNumbers([27]).WithSettings([new("wire", 31)])
            .WithChild(child).Build();
        if (references) value.Selection = child;
        AssertStaged(value, "replacement", explicitValues: true);

        var omitted = SnapshotCollectionBehaviorTests.Omission(format, nameof(InheritedManagedDerived));
        var supplied = SnapshotCollectionBehaviorTests.Write(value, references, format);
        Assert.Equal(["numbers:1", "settings:1", "hook:replacement", "numbers:2", "settings:2"],
            InheritedManagedDefaultsState.Phases);
        VerifyDocuments(omitted, supplied);
        if (directory is null) return;

        string Name(string author, string state) =>
            Path.Combine(directory, $"{author}-inherited-managed-{profile}-{state}.{extension}");
        File.WriteAllText(Name("csharp", "derived"), omitted);
        File.WriteAllText(Name("csharp", "overridden"), supplied);
        VerifyDocuments(File.ReadAllText(Name("java", "derived")), File.ReadAllText(Name("java", "overridden")));

        void VerifyDocuments(string omittedDocument, string suppliedDocument)
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                VerifyProfile(typedValues: false);
                if (!references) VerifyProfile(typedValues: true);
            }

            void VerifyProfile(bool typedValues)
            {
                InheritedManagedDefaultsState.Reset();
                var derived = SnapshotCollectionBehaviorTests.Read<InheritedManagedDerived>(
                    omittedDocument, references, format, typedValues);
                AssertStaged(derived, "references");

                InheritedManagedDefaultsState.Reset();
                var overridden = SnapshotCollectionBehaviorTests.Read<InheritedManagedOverridden>(
                    suppliedDocument, references, format, typedValues);
                AssertStaged(overridden, "replacement", explicitValues: true);
                Assert.Equal("child", overridden.Child?.Label);
                if (references) Assert.Same(overridden.Child, overridden.Selection);
                else Assert.Null(overridden.Selection);
            }
        }
    }

    private static void AssertStaged(InheritedManagedFields value, string kind, bool explicitValues = false, bool cleared = false)
    {
        int[] numbers = cleared ? [] : explicitValues ? [27] : [1];
        string? key = cleared ? null : explicitValues ? "wire" : "saved-1";
        int setting = explicitValues ? 31 : 1;
        string[] edits = cleared ? ["seed-a>", "seed-b>", ">saved-2", "saved-2>"]
            : ["seed-a>", "seed-b>", ">saved-2", "saved-2>", $">{key}"];
        AssertState(value, kind, true, numbers, key, setting, ["[99]>[2]", $"[2]>[{string.Join(", ", numbers)}]"], edits);
    }

    private static void AssertState(InheritedManagedFields value, string kind, bool staged,
        int[] numbers, string? key, int setting, string[] numbersEdits, string[] settingsEdits)
    {
        Assert.Same(value, InheritedManagedDefaultsState.LastOwner);
        Assert.Same(value.Numbers, InheritedManagedDefaultsState.LastNumbers);
        Assert.Same(value.Settings, InheritedManagedDefaultsState.LastSettings);
        Assert.Equal(numbers, value.Numbers);
        if (key is null) Assert.Empty(value.Settings);
        else Assert.Equal(new KeyValuePair<string, int?>(key, setting), Assert.Single(value.Settings));
        string[] phases = staged ? ["numbers:1", "settings:1", $"hook:{kind}", "numbers:2", "settings:2"]
            : [$"hook:{kind}", "numbers:1", "settings:1"];
        Assert.Equal(phases, InheritedManagedDefaultsState.Phases);
        Assert.Equal(numbersEdits, InheritedManagedDefaultsState.NumbersEdits);
        Assert.Equal(settingsEdits, InheritedManagedDefaultsState.SettingsEdits);
    }
}
