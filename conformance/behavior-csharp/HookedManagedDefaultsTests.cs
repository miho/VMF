using Eu.Mihosoft.Vmf.Conformance.Behavior;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class HookedManagedDefaultsTests
{
    private const string Type = "eu.mihosoft.vmf.conformance.behavior.HookedManagedDefaults";
    private static readonly string[] StagedPhases =
        ["numbers:1", "token:1", "values:1", "hook:0", "numbers:2", "token:2", "values:2"];
    private const string MapInitialization = "seed-a>|seed-b>|>saved-2|saved-2>";

    [Fact]
    public void NewAndBuilderConstructWithTheSamePhasesAsJava()
    {
        ManagedDefaultBehaviorState.Reset();
        var direct = new HookedManagedDefaults();
        Assert.Equal(["hook:0", "numbers:1", "token:1", "values:1"], ManagedDefaultBehaviorState.Phases);
        AssertState(direct, [1], 100, "saved-1", 1, "seed-a>|seed-b>|>saved-1");

        ManagedDefaultBehaviorState.Reset();
        var builder = HookedManagedDefaults.NewBuilder();
        Assert.Equal(["numbers:1", "token:1", "values:1"], ManagedDefaultBehaviorState.Phases);
        var built = builder.WithNumbers([27]).WithToken(57).WithValues([new("wire", 31)]).Build();
        Assert.Equal(StagedPhases, ManagedDefaultBehaviorState.Phases);
        AssertState(built, [27], 57, "wire", 31, MapInitialization + "|>wire");
    }

    [Theory]
    [InlineData("plain")]
    [InlineData("typed")]
    [InlineData("references")]
    public void SnapshotStagesDefaultsBeforeItsConstructorAndRetainsExplicitValues(string profile)
    {
        ManagedDefaultBehaviorState.Reset();
        var omitted = Read(profile);
        Assert.Equal(StagedPhases, ManagedDefaultBehaviorState.Phases);
        AssertState(omitted, [1], 100, "saved-1", 1, MapInitialization + "|>saved-1");
        if (profile == "references") Assert.Same(omitted, omitted.Link);

        ManagedDefaultBehaviorState.Reset();
        var partial = Read(profile, "\"token\":57");
        Assert.Equal(StagedPhases, ManagedDefaultBehaviorState.Phases);
        AssertState(partial, [1], 57, "saved-1", 1, MapInitialization + "|>saved-1");

        ManagedDefaultBehaviorState.Reset();
        var explicitValues = Read(profile, "\"numbers\":[27],\"token\":57,\"values\":{\"wire\":31}");
        Assert.Equal(StagedPhases, ManagedDefaultBehaviorState.Phases);
        AssertState(explicitValues, [27], 57, "wire", 31, MapInitialization + "|>wire");

        ManagedDefaultBehaviorState.Reset();
        var cleared = Read(profile, "\"numbers\":[],\"token\":0,\"values\":{}");
        Assert.Equal(StagedPhases, ManagedDefaultBehaviorState.Phases);
        Assert.Empty(cleared.Numbers);
        Assert.Equal(0, cleared.Token);
        Assert.Empty(cleared.Values);
        Assert.Equal(MapInitialization, cleared.ObservedMapEdits());
    }

    [Theory]
    [InlineData(false, VmfDocumentFormat.Json)]
    [InlineData(false, VmfDocumentFormat.Xml)]
    [InlineData(false, VmfDocumentFormat.Yaml)]
    [InlineData(true, VmfDocumentFormat.Json)]
    [InlineData(true, VmfDocumentFormat.Xml)]
    [InlineData(true, VmfDocumentFormat.Yaml)]
    public void ExchangesStatefulManagedDefaultsAcrossSnapshotFormats(bool references, VmfDocumentFormat format)
    {
        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR");
        string profile = references ? "refs" : "plain";
        string extension = format.ToString().ToLowerInvariant();
        foreach (string state in new[] { "omitted", "explicit", "cleared" })
        {
            bool cleared = state == "cleared";
            ManagedDefaultBehaviorState.Reset();
            string document;
            if (state == "omitted")
            {
                document = SnapshotCollectionBehaviorTests.Omission(format, nameof(HookedManagedDefaults));
            }
            else
            {
                var model = HookedManagedDefaults.NewBuilder().WithNumbers(cleared ? [] : [27])
                    .WithToken(cleared ? 0 : 57).WithValues(cleared ? [] : [new("wire", 31)]).Build();
                if (references) model.Link = model;
                document = SnapshotCollectionBehaviorTests.Write(model, references, format);
            }
            VerifyDocument(document, state);
            if (exchange is null) continue;

            string name = $"executable-defaults-{profile}-{state}.{extension}";
            VerifyDocument(File.ReadAllText(Path.Combine(exchange, "java-" + name)), state);
            File.WriteAllText(Path.Combine(exchange, "csharp-" + name), document);
        }

        void VerifyDocument(string document, string state)
        {
            ManagedDefaultBehaviorState.Reset();
            var restored = SnapshotCollectionBehaviorTests.Read<HookedManagedDefaults>(document, references, format);
            VerifyRestored(restored, state);
            if (!references)
            {
                ManagedDefaultBehaviorState.Reset();
                VerifyRestored(SnapshotCollectionBehaviorTests.Read<HookedManagedDefaults>(
                    document, references, format, typedValues: true), state);
            }
        }

        void VerifyRestored(HookedManagedDefaults restored, string state)
        {
            Assert.Equal(StagedPhases, ManagedDefaultBehaviorState.Phases);
            Assert.Same(restored, ManagedDefaultBehaviorState.LastOwner);
            if (references && state != "omitted") Assert.Same(restored, restored.Link);
            else Assert.Null(restored.Link);

            if (state == "omitted")
            {
                AssertState(restored, [1], 100, "saved-1", 1, MapInitialization + "|>saved-1");
            }
            else if (state == "explicit")
            {
                AssertState(restored, [27], 57, "wire", 31, MapInitialization + "|>wire");
            }
            else
            {
                Assert.Empty(restored.Numbers);
                Assert.Equal(0, restored.Token);
                Assert.Empty(restored.Values);
                Assert.Equal(MapInitialization, restored.ObservedMapEdits());
            }
        }
    }

    [Fact]
    public void AlreadySupportedModelWithoutHookAlsoRetainsItsStagedValues()
    {
        ManagedDefaultBehaviorState.Reset();
        var model = ModelSnapshot.ReadPlain<UnhookedManagedDefaults>("{}");
        Assert.Equal(["numbers:1", "values:1", "numbers:2", "values:2"], ManagedDefaultBehaviorState.Phases);
        Assert.Equal(1, Assert.Single(model.Numbers));
        Assert.Equal(1, model.Values["saved-1"]);
    }

    [Fact]
    public void AThrowingMapCallbackStopsAfterTheCommittedRemoval()
    {
        ManagedDefaultBehaviorState.Reset();
        ManagedDefaultBehaviorState.ThrowOnRemoval = true;
        var error = Assert.Throws<InvalidOperationException>(() => Read("plain"));
        Assert.Equal("stop after seed-a", error.Message);
        Assert.Equal(StagedPhases, ManagedDefaultBehaviorState.Phases);
        var owner = Assert.IsType<HookedManagedDefaults>(ManagedDefaultBehaviorState.LastOwner);
        AssertState(owner, [2], 200, "seed-b", 2, "seed-a>");
    }

    private static void AssertState(HookedManagedDefaults model, int[] numbers, int token,
        string key, int value, string events)
    {
        Assert.Equal(numbers, model.Numbers);
        Assert.Equal(token, model.Token);
        var entry = Assert.Single(model.Values);
        Assert.Equal(key, entry.Key);
        Assert.Equal(value, entry.Value);
        Assert.Equal(events, model.ObservedMapEdits());
    }

    private static HookedManagedDefaults Read(string profile, string fields = "")
    {
        string metadata = profile switch
        {
            "typed" => $"\"@vmf-type\":\"{Type}\"",
            "references" => $"\"@vmf-type\":\"{Type}\",\"@vmf-id\":\"root\",\"link\":\"root\"",
            _ => "",
        };
        string separator = metadata.Length != 0 && fields.Length != 0 ? "," : "";
        string document = "{" + metadata + separator + fields + "}";
        return profile switch
        {
            "plain" => ModelSnapshot.ReadPlain<HookedManagedDefaults>(document),
            "typed" => ModelSnapshot.ReadWithoutReferences<HookedManagedDefaults>(document),
            "references" => ModelSnapshot.Read<HookedManagedDefaults>(document),
            _ => throw new ArgumentOutOfRangeException(nameof(profile)),
        };
    }
}
