using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Referencedefaults;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class ReferenceDefaultTests
{
    private const string Checkpoint = "reference-defaults-40";

    private static readonly JournalBinding Binding = new("conformance.reference-defaults", 1, model => model switch
    {
        ReferenceDefaultDocument => "document",
        MutablePicks => "mutable",
        FrozenPicks => "frozen",
        SelectionTarget { Code: { } code } => code,
        SelectionToken { Key: { } key } => key,
        _ => throw new ArgumentException("Unregistered reference-default object")
    });

    [Fact]
    public void OwnersAndBuildersCaptureEachDeclaredProviderOnce()
    {
        int before = ReferenceDefaultBindings.MutableCalls;
        var owner = new MutablePicks();
        var favorites = Field(owner, "favorites");
        var fallback = Members(favorites);
        var initial = Assert.IsType<SelectionTarget>(Assert.Single(fallback));
        Assert.Same(initial, Assert.Single(owner.Favorites));
        Assert.Same(fallback, favorites.DefaultValue);
        Assert.False(favorites.IsSet);
        Assert.Throws<NotSupportedException>(() => ((ICollection<SelectionTarget>)fallback).Clear());
        Assert.Same(initial.AsReadOnly(), Assert.Single(Members(Field(owner.AsReadOnly(), "favorites"))));
        owner.Favorites.Clear();
        Assert.True(favorites.IsSet);
        favorites.Reset();
        Assert.Same(initial, Assert.Single(owner.Favorites));
        Assert.Equal(before + 2, ReferenceDefaultBindings.MutableCalls);

        before = ReferenceDefaultBindings.MutableCalls;
        var staged = MutablePicks.NewBuilder().WithFavorites([]);
        var first = staged.Build();
        var second = staged.Build();
        Assert.Equal(before + 2, ReferenceDefaultBindings.MutableCalls);
        Assert.Empty(first.Favorites);
        Assert.Same(Field(first, "favorites").DefaultValue, Field(second, "favorites").DefaultValue);

        before = ReferenceDefaultBindings.ImmutableCalls;
        var frozenBuilder = FrozenPicks.NewBuilder();
        var frozen = frozenBuilder.Build();
        var frozenDefault = Members(Field(frozen, "featured"));
        Assert.Equal(before + 2, ReferenceDefaultBindings.ImmutableCalls);
        Assert.Same(frozenDefault, Field(frozen, "featured").DefaultValue);
        Assert.Same(frozenDefault[0], frozen.Featured[0]);
        Assert.NotSame(frozen.Featured[0], frozen.Featured[1]);
        Assert.Equal(frozen.Featured[0], frozen.Featured[1]);
        Assert.Same(frozen, frozen.AsReadOnly());
        Assert.False(Field(frozen, "favorites").CanSetDefault);

        ReferenceDefaultBindings.DuplicateSeed = true;
        try
        {
            Assert.Throws<ArgumentException>(() => new MutablePicks());
            Assert.Throws<ArgumentException>(() => MutablePicks.NewBuilder());
        }
        finally
        {
            ReferenceDefaultBindings.DuplicateSeed = false;
        }
    }

    [Fact]
    public void NestedImmutableBuildersKeepTheCapturedReferenceDefaults()
    {
        int before = ReferenceDefaultBindings.ImmutableCalls;
        var child = SelectionToken.NewBuilder().WithKey("nested").WithLabel("explicit");
        var staged = FrozenPicks.NewBuilder().WithFavoritesBuilders([child]).WithFeaturedBuilders([]);
        var first = staged.Build();
        var second = staged.Build();
        Assert.Equal(before + 2, ReferenceDefaultBindings.ImmutableCalls);
        Assert.Empty(first.Featured);
        Assert.Empty(second.Featured);
        Assert.Equal("nested", Assert.Single(first.Favorites).Key);
        Assert.NotSame(Assert.Single(first.Favorites), Assert.Single(second.Favorites));
        Assert.Same(Field(first, "featured").DefaultValue, Field(second, "featured").DefaultValue);
        Assert.Same(Field(first, "favorites").DefaultValue, Field(second, "favorites").DefaultValue);
        Assert.DoesNotContain(Members(Field(first, "favorites")), value => ReferenceEquals(value, Assert.Single(first.Favorites)));
    }

    [Fact]
    public void FrozenBuilderImportsAppendToCapturedReferenceDefaults()
    {
        int before = ReferenceDefaultBindings.ImmutableCalls;
        var builder = FrozenPicks.NewBuilder();
        var seed = builder.Build();
        var favorite = seed.Favorites.First();
        var featured = seed.Featured[0];
        var incoming = ReferenceDefaultBindings.Token("incoming", "seed");
        Assert.Equal(favorite, incoming);
        Assert.NotSame(favorite, incoming);
        var source = FrozenPicks.NewBuilder().WithFavorites([favorite, incoming])
            .WithFeatured([featured, incoming]).Build();

        var appended = builder.ApplyFrom(source).Build();
        Assert.Equal(3, appended.Favorites.Count);
        Assert.All(seed.Favorites, member => Assert.True(appended.Favorites.Contains(member)));
        Assert.True(appended.Favorites.Contains(incoming));
        Assert.Equal(3, appended.Featured.Count);
        Assert.Same(featured, appended.Featured[0]);
        Assert.Same(seed.Featured[1], appended.Featured[1]);
        Assert.Same(incoming, appended.Featured[2]);
        Assert.Same(Field(seed, "favorites").DefaultValue, Field(appended, "favorites").DefaultValue);
        Assert.Same(Field(seed, "featured").DefaultValue, Field(appended, "featured").DefaultValue);

        var replaced = builder.AppendCollections(false).ApplyFrom(source).Build();
        Assert.Equal(2, replaced.Favorites.Count);
        Assert.Same(incoming, replaced.Featured[1]);
        var nested = SelectionToken.NewBuilder().WithKey("lazy");
        var afterNested = builder.AppendCollections(true).WithFavoritesBuilders([nested])
            .WithFeaturedBuilders([nested]).ApplyFrom(source).Build();
        Assert.Equal(2, afterNested.Favorites.Count);
        Assert.Equal(2, afterNested.Featured.Count);
        Assert.Same(incoming, afterNested.Featured[1]);
        Assert.Same(Field(seed, "featured").DefaultValue, Field(afterNested, "featured").DefaultValue);
        Assert.Equal(before + 4, ReferenceDefaultBindings.ImmutableCalls);
        Assert.Equal(2, seed.Featured.Count);
    }


    [Fact]
    public void NestedMutableBuildersStageMembershipWithoutReevaluatingDefaults()
    {
        int before = ReferenceDefaultBindings.MutableCalls;
        var inner = SelectionTarget.NewBuilder().WithCode("nested");
        var stage = MutablePicks.NewBuilder().WithFavoritesBuilders([inner]).WithFeaturedBuilders([]);
        Assert.Equal(before + 2, ReferenceDefaultBindings.MutableCalls);

        var target = new MutablePicks();
        var defaultMember = Assert.Single(target.Favorites);
        stage.ApplyTo(target);
        Assert.Same(defaultMember, Assert.Single(target.Favorites));
        Assert.Equal(2, target.Featured.Count);
        stage.AppendCollections(false).ApplyTo(target);
        Assert.Empty(target.Favorites);
        Assert.Empty(target.Featured);

        var first = stage.Build();
        var second = stage.Build();
        var member = Assert.Single(first.Favorites);
        Assert.Equal("nested", member.Code);
        Assert.NotSame(member, Assert.Single(second.Favorites));
        Assert.Empty(first.Featured);
        Assert.Same(Field(first, "favorites").DefaultValue, Field(second, "favorites").DefaultValue);
        Assert.Equal(before + 4, ReferenceDefaultBindings.MutableCalls);
        Assert.Same(member, Assert.Single(stage.ApplyFrom(first).Build().Favorites));
        Assert.Equal(before + 4, ReferenceDefaultBindings.MutableCalls);
    }

    [Fact]
    public void CopiesRemapLatentDeclaredAndConfiguredReferenceDefaults()
    {
        var source = new MutablePicks();
        var favorite = Field(source, "favorites");
        var first = Assert.IsType<SelectionTarget>(Assert.Single(Members(favorite)));
        var linked = source.Featured[0];
        first.Link = linked;
        linked.Link = first;
        int calls = ReferenceDefaultBindings.MutableCalls;
        var deep = ModelSnapshot.Reflection.DeepCopy(source);
        var shallow = ModelSnapshot.Reflection.ShallowCopy(source);
        Assert.NotSame(source.Favorites, shallow.Favorites);
        Assert.Same(favorite.DefaultValue, Field(shallow, "favorites").DefaultValue);
        Assert.Same(first, Assert.Single(shallow.Favorites));
        var cloned = Assert.Single(deep.Favorites);
        Assert.NotSame(first, cloned);
        Assert.Same(cloned, Assert.Single(Members(Field(deep, "favorites"))));
        Assert.Same(deep.Featured[0], cloned.Link);
        Assert.Same(cloned, cloned.Link!.Link);

        source.Favorites.Clear();
        source.Featured.Clear();
        var configured = ReferenceDefaultBindings.Target("override");
        configured.Link = first;
        favorite.SetDefault(new[] { configured });
        var empty = ModelSnapshot.Reflection.DeepCopy(source);
        var field = Field(empty, "favorites");
        var clonedOverride = Assert.IsType<SelectionTarget>(Assert.Single(Members(field)));
        Assert.NotSame(configured, clonedOverride);
        Assert.NotSame(first, clonedOverride.Link);
        Assert.Empty(empty.Favorites);
        field.SetDefault(null);
        Assert.Same(clonedOverride.Link, Assert.Single(Members(field)));
        field.Reset();
        Assert.Same(clonedOverride.Link, Assert.Single(empty.Favorites));
        Assert.Equal(calls, ReferenceDefaultBindings.MutableCalls);
    }

    [Fact]
    public void ExplicitEmptyReferenceFieldsLeaveTheCapturedDefaultIntact()
    {
        var missing = ModelSnapshot.Read<MutablePicks>("{}");
        Assert.Single(missing.Favorites);
        Assert.Equal(2, missing.Featured.Count);
        var empty = ModelSnapshot.Read<MutablePicks>("""{"picked":[],"featured":[]}""");
        Assert.Empty(empty.Favorites);
        Assert.Empty(empty.Featured);
        var favorite = Field(empty, "favorites");
        var reset = Assert.Single(Members(favorite));
        favorite.Reset();
        Assert.Same(reset, Assert.Single(empty.Favorites));
        Assert.Throws<JsonException>(() => ModelSnapshot.Write(empty));
    }

    [Fact]
    public void LegacyReciprocalDocumentsStillRejectConflictingExplicitMemberships()
    {
        var member = new LegacyMember();
        var group = new LegacyGroup();
        group.Members.Add(member);
        var document = new LegacyReciprocalDocument { Group = group, Member = member };
        var data = JsonNode.Parse(ModelSnapshot.Write(document))!.AsObject();
        data["member"]!.AsObject()["groups"] = new JsonArray();

        var error = Assert.Throws<JsonException>(() => ModelSnapshot.Read<LegacyReciprocalDocument>(data.ToJsonString()));
        Assert.Contains("Invalid reciprocal references", error.Message);
        Assert.Contains("Conflicting reciprocal membership", error.InnerException!.Message);
    }

    [Fact]
    public void MembershipJournalsReplayExplicitSelectionsAndRemapCheckpointLocalFallbacks()
    {
        var alpha = ReferenceDefaultBindings.Target("alpha");
        var beta = ReferenceDefaultBindings.Target("beta");
        var first = ReferenceDefaultBindings.Token("token-first", "explicit");
        var second = ReferenceDefaultBindings.Token("token-second", "explicit");
        var specimen = ReferenceDefaultDocument.NewBuilder().WithTargets([alpha, beta])
            .WithMutable(MutablePicks.NewBuilder().WithFavorites([alpha]).WithFeatured([alpha, beta]).Build())
            .WithFrozen(FrozenPicks.NewBuilder().WithFavorites([first, second]).WithFeatured([second, first]).Build()).Build();
        string checkpoint = ModelSnapshot.Write(specimen);
        using var recorder = JournalRecorder<ReferenceDefaultDocument>.Start(specimen, ModelSnapshot.Registry, Binding, Checkpoint, 40);
        specimen.Mutable!.Favorites.Remove(alpha);
        specimen.Mutable.Favorites.Add(beta);
        specimen.Mutable.Featured.Move(1, 0);
        var journal = recorder.Finish();
        Assert.Equal(1, journal.WireVersion);
        string json = JournalJson.Write(journal);
        string xml = JournalXml.Write(journal);
        Verify(checkpoint, JournalJson.Read(json));
        Verify(checkpoint, JournalXml.Read(xml), ModelSnapshot.Registry.WithExternalValueCodecs(ExternalSnapshotCodecs.Empty));

        string? exchange = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_REFERENCE_DEFAULTS");
        if (exchange is null) return;
        string incoming = File.ReadAllText(Path.Combine(exchange, "java-reference-default-checkpoint.json"));
        Verify(incoming, JournalJson.Read(File.ReadAllText(Path.Combine(exchange, "java-reference-default-journal.json"))));
        Verify(incoming, JournalXml.Read(File.ReadAllText(Path.Combine(exchange, "java-reference-default-journal.xml"))));
        File.WriteAllText(Path.Combine(exchange, "csharp-reference-default-checkpoint.json"), checkpoint);
        File.WriteAllText(Path.Combine(exchange, "csharp-reference-default-journal.json"), json);
        File.WriteAllText(Path.Combine(exchange, "csharp-reference-default-journal.xml"), xml);
    }

    private static void Verify(string document, ChangeJournal journal, SnapshotRegistry? registry = null)
    {
        var checkpoint = ModelSnapshot.Read<ReferenceDefaultDocument>(document);
        var alpha = checkpoint.Targets[0];
        var beta = checkpoint.Targets[1];
        var mutable = checkpoint.Mutable!;
        Assert.Same(alpha, Assert.Single(mutable.Favorites));
        Assert.Same(alpha, mutable.Featured[0]);
        Assert.Same(beta, mutable.Featured[1]);
        var cached = Assert.IsType<SelectionTarget>(Assert.Single(Members(Field(mutable, "favorites"))));
        Assert.NotSame(alpha, cached);
        Assert.NotSame(beta, cached);
        Assert.Equal("default-favorite", cached.Code);
        cached.Code = "qualified-cache";

        var frozen = checkpoint.Frozen!;
        Assert.Equal(frozen.Featured[0], frozen.Featured[1]);
        Assert.NotSame(frozen.Featured[0], frozen.Featured[1]);
        Assert.True(frozen.Favorites.Contains(frozen.Featured[0]));
        Assert.True(frozen.Favorites.Contains(frozen.Featured[1]));

        int calls = ReferenceDefaultBindings.MutableCalls;
        var replayed = JournalReplayer.Replay(checkpoint, Binding, Checkpoint, journal, registry ?? ModelSnapshot.Registry);
        Assert.Equal(calls, ReferenceDefaultBindings.MutableCalls);
        Assert.Same(replayed.Targets[1], Assert.Single(replayed.Mutable!.Favorites));
        Assert.Same(replayed.Targets[1], replayed.Mutable.Featured[0]);
        Assert.Same(alpha, Assert.Single(mutable.Favorites));
        var after = Assert.IsType<SelectionTarget>(Assert.Single(Members(Field(replayed.Mutable, "favorites"))));
        Assert.NotSame(cached, after);
        Assert.Equal("qualified-cache", after.Code);
    }

    private static IReadOnlyList<object?> Members(ModelProperty property) =>
        Assert.IsAssignableFrom<IReadOnlyList<object?>>(property.DefaultValue);

    private static ModelProperty Field(object owner, string name) => ModelSnapshot.Reflection.Reflect(owner).FindProperty(name)!;
}
