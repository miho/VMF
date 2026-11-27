using Eu.Mihosoft.Vmf.Conformance.Values;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class DynamicDefaultsTests
{
    [Fact]
    public void HeldViewReadsTheEffectiveScalarDefaultAndSetState()
    {
        Check("name", "idle\nready", "custom", "replacement", null, view => view.Name);
        Check("baseline", -0.0d, 1.5d, 2.5d, -1.0d, view => view.Baseline);

        static void Check(string name, object declared, object configured, object replacement, object? explicitValue,
            Func<IReadOnlyDefaultSettings, object?> get)
        {
            var model = new DefaultSettings();
            var source = Field(model, name);
            var view = model.AsReadOnly();
            var projected = Field(view, name);
            CheckState(declared, declared, false);

            source.SetDefault(configured);
            CheckState(configured, configured, false);
            source.Set(explicitValue);
            CheckState(configured, explicitValue, true);
            source.SetDefault(replacement);
            CheckState(replacement, explicitValue, true);

            Assert.Throws<InvalidOperationException>(() => projected.SetDefault(configured));
            Assert.Throws<InvalidOperationException>(projected.Reset);
            CheckState(replacement, explicitValue, true);
            source.Reset();
            CheckState(replacement, replacement, false);

            Assert.Equal(declared, Field(new DefaultSettings(), name).DefaultValue);
            source.SetDefault(null);
            CheckState(declared, declared, false);

            void CheckState(object expectedDefault, object? expectedValue, bool set)
            {
                Assert.Equal(expectedDefault, source.DefaultValue);
                Assert.Equal(expectedDefault, projected.DefaultValue);
                Assert.Equal(expectedValue, source.Get());
                Assert.Equal(expectedValue, projected.Get());
                Assert.Equal(expectedValue, get(view));
                Assert.Equal(set, source.IsSet);
                Assert.Equal(set, projected.IsSet);
            }
        }
    }

    [Fact]
    public void UpdatesAnUnsetFieldThroughTheSetterAndKeepsASetField()
    {
        var model = new DefaultSettings();
        var name = Field(model, "name");
        var view = Field(model.AsReadOnly(), "name");
        var events = new List<(object? Before, object? After, object? Default)>();
        using var listener = model.VmfChanges.AddPropertyListener("name", change =>
        {
            Assert.Equal(ModelChangeKind.Property, change.Kind);
            events.Add((change.OldValue, change.NewValue, name.DefaultValue));
        });
        model.VmfChanges.Start();

        name.SetDefault("custom");
        Assert.False(name.IsSet);
        Assert.Equal("custom", view.DefaultValue);
        Assert.False(view.IsSet);
        Assert.Equal("custom", model.Name);
        Assert.Equal(("idle\nready", "custom", "custom"), Assert.Single(events));

        model.Name = "explicit";
        name.SetDefault("explicit");
        Assert.False(name.IsSet);
        Assert.Equal(2, events.Count);
        name.SetDefault(null);
        Assert.Equal("idle\nready", model.Name);
        Assert.Equal("idle\nready", name.DefaultValue);
        Assert.False(name.IsSet);
        Assert.Equal(3, model.VmfChanges.History.Count);
        model.VmfChanges.Stop();
        Assert.Equal(3, Assert.Single(model.VmfChanges.Transactions).Changes.Count);
        Assert.Equal("idle\nready", Field(new DefaultSettings(), "name").DefaultValue);
        Assert.False(view.CanSetDefault);
        Assert.Throws<InvalidOperationException>(() => view.SetDefault("hidden"));
    }

    [Fact]
    public void RejectsInvalidDefaultsBeforeChangingCurrentValuesOrMetadata()
    {
        var model = new DefaultSettings();
        var limit = Field(model, "limit");
        Assert.True(limit.CanSetDefault);
        limit.SetDefault(12);
        Assert.Throws<ArgumentException>(() => limit.SetDefault("invalid"));
        Assert.Equal(12, limit.DefaultValue);
        Assert.Equal(12, model.Limit);
        Assert.False(limit.IsSet);

        var profile = new CollectionProfile();
        var counts = Field(profile, "counts");
        counts.SetDefault(new[] { 1, 2 });
        Assert.Throws<ArgumentException>(() => counts.SetDefault(new[] { 5, 6, 7, 8 }));
        Assert.Throws<ArgumentException>(() => counts.SetDefault(new int?[] { null }));
        Assert.Equal(new[] { 1, 2 }, profile.Counts.ToArray());
        Assert.Equal(new object?[] { 1, 2 }, Assert.IsAssignableFrom<IReadOnlyList<object?>>(counts.DefaultValue).ToArray());
        Assert.False(counts.IsSet);

        var owner = new ArchiveNode();
        Assert.False(Field(owner, "inset").CanSetDefault);
        Assert.False(Field(owner, "children").CanSetDefault);
        Assert.False(Field(owner, "parent").CanSetDefault);
        Assert.Throws<InvalidOperationException>(() => Field(owner, "inset").SetDefault(new ArchiveNode()));
        Assert.Throws<InvalidOperationException>(() => Field(owner, "parent").SetDefault(new ArchiveNode()));
        Assert.Null(owner.Inset);
        Assert.Null(owner.Parent);
    }

    [Fact]
    public void SnapshotsListsSetsAndMapsAndRestoresTheirNativeContents()
    {
        var model = new CollectionProfile();
        var counts = Field(model, "counts");
        var list = model.Counts;
        var proposed = new List<int> { 2, 1 };
        counts.SetDefault(proposed);
        proposed.Clear();
        Assert.Equal(new[] { 2, 1 }, list.ToArray());
        Assert.False(counts.IsSet);
        list.Move(0, 1);
        Assert.True(counts.IsSet);
        counts.Reset();
        Assert.Same(list, model.Counts);
        Assert.Equal(new[] { 2, 1 }, model.Counts.ToArray());

        var ordinary = Field(model, "messages");
        ordinary.SetDefault(new string?[] { "same", null, "same" });
        Assert.Equal(new string?[] { "same", null, "same" }, model.Messages.ToArray());
        var ordered = Field(model, "phases");
        Assert.Throws<ArgumentException>(() => ordered.SetDefault(new[] { "draft", "draft" }));
        ordered.SetDefault(new[] { "draft", "ready" });
        Assert.False(ordered.IsSet);

        var levels = Field(model, "levels");
        levels.SetDefault(new[] { 0.0, -0.0, double.NaN, double.NaN });
        Assert.Equal(3, model.Levels.Count);
        Assert.False(levels.IsSet);
        model.Levels.Remove(-0.0);
        Assert.True(levels.IsSet);
        levels.Reset();
        Assert.Contains(model.Levels, number => BitConverter.DoubleToInt64Bits(number) == long.MinValue);

        var limits = Field(model, "limits");
        var source = new Dictionary<string, long?> { ["pending"] = null, ["value"] = 2L };
        limits.SetDefault(source);
        source["pending"] = 9;
        var defaultMap = Assert.IsAssignableFrom<IReadOnlyDictionary<object, object?>>(limits.DefaultValue);
        Assert.True(defaultMap.ContainsKey("pending"));
        Assert.Null(defaultMap["pending"]);
        Assert.Throws<NotSupportedException>(() => ((IDictionary<object, object?>)defaultMap).Add("hidden", 2L));
        model.Limits.Remove("pending");
        model.Limits.Add("pending", null);
        Assert.False(limits.IsSet);
        limits.Reset();
        Assert.Equal(new[] { "pending", "value" }, model.Limits.Keys.ToArray());
        Assert.Null(model.Limits["pending"]);
        Assert.Equal(2L, model.Limits["value"]);
    }

    [Fact]
    public void ReadOnlyDefaultsProjectTheSameReferenceIdentities()
    {
        var model = new ArchiveNode();
        var reference = Field(model, "anchor");
        var peers = Field(model, "peers");
        var featured = Field(model, "featured");
        var trail = Field(model, "trail");
        var target = new ArchiveNode { Name = "equal" };
        var equal = new ArchiveNode { Name = "equal" };
        reference.SetDefault(target);
        peers.SetDefault(new[] { target, equal });
        featured.SetDefault(new[] { equal, target });
        trail.SetDefault(new ArchiveNode?[] { target, null, target });
        Assert.Throws<ArgumentException>(() => peers.SetDefault(new[] { equal, equal }));
        Assert.Throws<ArgumentException>(() => featured.SetDefault(new[] { equal, equal }));
        Assert.False(reference.IsSet);
        Assert.False(peers.IsSet);
        Assert.False(featured.IsSet);
        Assert.False(trail.IsSet);

        var view = model.AsReadOnly();
        Assert.Same(target.AsReadOnly(), Field(view, "anchor").DefaultValue);
        var defaults = Assert.IsAssignableFrom<IReadOnlyList<object?>>(Field(view, "trail").DefaultValue);
        Assert.Same(target.AsReadOnly(), defaults[0]);
        Assert.Null(defaults[1]);
        Assert.Same(defaults[0], defaults[2]);
        model.Anchor = equal;
        Assert.True(reference.IsSet);
        Assert.True(Field(view, "anchor").IsSet);
        reference.Reset();
        Assert.Same(target, model.Anchor);
    }

    [Fact]
    public void CopiesPreserveManagedScalarCollectionAndMapOverrides()
    {
        var model = new CollectionProfile();
        Field(model, "counts").SetDefault(new[] { 2 });
        Field(model, "phases").SetDefault(new[] { "draft", "ready" });
        Field(model, "levels").SetDefault(new[] { 0.0, -0.0, double.NaN });
        Field(model, "limits").SetDefault(new Dictionary<string, long?> { ["pending"] = null, ["value"] = 2L });
        model.Counts.Add(3);
        int sourceChanges = 0;
        using var observer = model.VmfChanges.AddLocalListener(_ => sourceChanges++);

        foreach (var copy in new[] { ModelSnapshot.Reflection.DeepCopy(model), ModelSnapshot.Reflection.ShallowCopy(model) })
        {
            Assert.NotSame(model.Counts, copy.Counts);
            Assert.NotSame(model.Limits, copy.Limits);
            Assert.NotSame(model.Levels, copy.Levels);
            Assert.NotSame(model.Phases, copy.Phases);

            var counts = Field(copy, "counts");
            Assert.Equal(new[] { 2, 3 }, copy.Counts.ToArray());
            Assert.Equal(2, Assert.Single(DefaultMembers(counts)));
            Assert.True(counts.IsSet);
            counts.Reset();
            Assert.False(counts.IsSet);
            Assert.Equal(2, Assert.Single(copy.Counts));

            var phases = Field(copy, "phases");
            Assert.False(phases.IsSet);
            copy.Phases.Move(0, 1);
            Assert.True(phases.IsSet);
            phases.Reset();
            Assert.Equal(new[] { "draft", "ready" }, copy.Phases.ToArray());

            var levels = Field(copy, "levels");
            Assert.False(levels.IsSet);
            Assert.True(copy.Levels.Remove(-0.0));
            levels.Reset();
            Assert.False(levels.IsSet);
            Assert.Contains(copy.Levels, value => BitConverter.DoubleToInt64Bits(value) == long.MinValue);
            Assert.Contains(copy.Levels, double.IsNaN);

            var limits = Field(copy, "limits");
            Assert.False(limits.IsSet);
            copy.Limits.Remove("pending");
            limits.Reset();
            Assert.True(copy.Limits.ContainsKey("pending"));
            Assert.Null(copy.Limits["pending"]);
            limits.SetDefault(new Dictionary<string, long?> { ["override"] = 5L });
            Assert.False(limits.IsSet);
            Assert.False(model.Limits.ContainsKey("override"));
        }
        Assert.Equal(0, sourceChanges);
        Assert.Equal(new[] { 2, 3 }, model.Counts.ToArray());
        Assert.Equal(2, Assert.Single(DefaultMembers(Field(model, "counts"))));
    }

    [Fact]
    public void CopiesKeepTheLiveValuesButDropLegacyDefaultOverrides()
    {
        var profile = new CollectionProfile();
        Field(profile, "messages").SetDefault(new string?[] { "same", null, "same" });
        var node = new ArchiveNode();
        var target = new ArchiveNode { Name = "target" };
        Field(node, "name").SetDefault("runtime-name");
        Field(node, "anchor").SetDefault(target);
        Field(node, "trail").SetDefault(new ArchiveNode?[] { target, null, target });

        foreach (var copy in new[] { ModelSnapshot.Reflection.DeepCopy(profile), ModelSnapshot.Reflection.ShallowCopy(profile) })
        {
            Assert.Equal(new string?[] { "same", null, "same" }, copy.Messages.ToArray());
            Assert.Empty(DefaultMembers(Field(copy, "messages")));
            Assert.True(Field(copy, "messages").IsSet);
        }
        foreach (var copy in new[] { ModelSnapshot.Reflection.DeepCopy(node), ModelSnapshot.Reflection.ShallowCopy(node) })
        {
            Assert.Equal("runtime-name", copy.Name);
            Assert.Null(Field(copy, "name").DefaultValue);
            Assert.True(Field(copy, "name").IsSet);
            Assert.NotNull(copy.Anchor);
            Assert.Null(Field(copy, "anchor").DefaultValue);
            Assert.True(Field(copy, "anchor").IsSet);
            Assert.Same(copy.Anchor, copy.Trail[0]);
            Assert.Null(copy.Trail[1]);
            Assert.Same(copy.Trail[0], copy.Trail[2]);
            Assert.Empty(DefaultMembers(Field(copy, "trail")));
            Assert.True(Field(copy, "trail").IsSet);
        }
    }

    [Fact]
    public void IdentitySetDefaultsUseTheSameDeepCopyIdentitiesAsLiveEdges()
    {
        var model = new ArchiveNode { Name = "root" };
        var existing = new ArchiveNode { Name = "same" };
        var defaultOnly = new ArchiveNode { Name = "same", Anchor = model };
        model.Anchor = existing;
        model.Featured.Add(existing);
        model.Peers.Add(existing);
        Field(model, "featured").SetDefault(new[] { existing, defaultOnly });
        Field(model, "peers").SetDefault(new[] { existing, defaultOnly });

        var deep = ModelSnapshot.Reflection.DeepCopy(model);
        var shallow = ModelSnapshot.Reflection.ShallowCopy(model);
        var deepDefault = Assert.IsType<ArchiveNode>(Assert.Single(DefaultMembers(Field(deep, "featured")).Skip(1)));
        Assert.NotSame(defaultOnly, deepDefault);
        Assert.NotSame(existing, deep.Anchor);
        Assert.Same(deep, deepDefault.Anchor);
        Assert.Same(existing, shallow.Anchor);
        Assert.Same(defaultOnly, Assert.Single(DefaultMembers(Field(shallow, "featured")).Skip(1)));

        foreach (var copy in new[] { deep, shallow })
        {
            var featured = Field(copy, "featured");
            var peers = Field(copy, "peers");
            var members = DefaultMembers(featured);
            Assert.Same(copy.Anchor, Assert.Single(copy.Featured));
            Assert.Same(copy.Anchor, Assert.Single(copy.Peers));
            Assert.Same(copy.Anchor, members[0]);
            Assert.Same(members[1], DefaultMembers(peers)[1]);
            Assert.True(featured.IsSet);
            Assert.True(peers.IsSet);
            Assert.Same(copy.Anchor!.AsReadOnly(), DefaultMembers(Field(copy.AsReadOnly(), "featured"))[0]);
            featured.Reset();
            peers.Reset();
            Assert.False(featured.IsSet);
            Assert.False(peers.IsSet);
            Assert.Same(members[1], copy.Featured[1]);
            Assert.Contains(copy.Peers, value => ReferenceEquals(members[1], value));
        }
        Assert.Same(existing, Assert.Single(model.Featured));
        Assert.Same(existing, Assert.Single(model.Peers));
        var view = ModelSnapshot.Reflection.DeepCopy(model.AsReadOnly());
        Assert.Same(Assert.Single(view.Featured), DefaultMembers(Field(view, "featured"))[0]);
    }

    [Fact]
    public void SnapshotsPersistLiveDataAndRecreateDeclarationDefaults()
    {
        var profile = new CollectionProfile();
        Field(profile, "counts").SetDefault(new[] { 2 });
        Field(profile, "limits").SetDefault(new Dictionary<string, long?> { ["pending"] = null });
        var restored = ModelSnapshot.ReadPlain<CollectionProfile>(ModelSnapshot.WritePlain(profile));
        Assert.Equal(2, Assert.Single(restored.Counts));
        Assert.Empty(DefaultMembers(Field(restored, "counts")));
        Assert.Empty(Assert.IsAssignableFrom<IReadOnlyDictionary<object, object?>>(Field(restored, "limits").DefaultValue));
        Assert.True(restored.Limits.ContainsKey("pending"));
        Assert.Null(restored.Limits["pending"]);
        Assert.True(Field(restored, "counts").IsSet);

        var node = new ArchiveNode { Name = "root" };
        var target = new ArchiveNode { Name = "contained" };
        node.Children.Add(target);
        node.Peers.Add(target);
        Field(node, "peers").SetDefault(new[] { target, new ArchiveNode { Name = "default-only" } });
        var loaded = ModelSnapshot.Read<ArchiveNode>(ModelSnapshot.Write(node));
        Assert.Same(Assert.Single(loaded.Children), Assert.Single(loaded.Peers));
        Assert.Empty(DefaultMembers(Field(loaded, "peers")));
        Assert.True(Field(loaded, "peers").IsSet);
    }

    private static IReadOnlyList<object?> DefaultMembers(ModelProperty property) =>
        Assert.IsAssignableFrom<IReadOnlyList<object?>>(property.DefaultValue);

    private static ModelProperty Field(object model, string name) => ModelSnapshot.Reflection.Reflect(model).FindProperty(name)!;
}
