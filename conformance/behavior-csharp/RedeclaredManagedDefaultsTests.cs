using Eu.Mihosoft.Vmf.Conformance.Behavior;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed partial class InheritedManagedDefaultsTests
{
    [Fact]
    public void RedeclaredManagedCollectionsImportParentStorageWithoutEvaluatingOldDefaults()
    {
        InheritedManagedDefaultsState.Reset();
        var parent = new InheritedManagedReferences();
        InheritedManagedDefaultsState.Reset();
        var imported = RedeclaredManagedReplacement.NewBuilder().AppendCollections(false).ApplyFrom(parent).Build();
        Assert.Equal([1], imported.Numbers);
        Assert.Equal(new KeyValuePair<string, int?>("saved-1", 1), Assert.Single(imported.Settings));
        Assert.Equal(["alternative-numbers:1", "alternative-settings:1", "hook:references",
            "alternative-numbers:2", "alternative-settings:2"], InheritedManagedDefaultsState.Phases);
    }

    [Fact]
    public void ManagedCollectionRedeclarationsSelectEffectiveConstructorBuilderAndSnapshotDefaults()
    {
        Verify("replacement", () => new RedeclaredManagedReplacement(),
            () => RedeclaredManagedReplacement.NewBuilder().Build());
        Verify("empty", () => new RedeclaredManagedEmpty(), () => RedeclaredManagedEmpty.NewBuilder().Build());
        Verify("none", () => new RedeclaredManagedNone(), () => RedeclaredManagedNone.NewBuilder().Build());
        Verify("mixed", () => new RedeclaredManagedMixed(), () => RedeclaredManagedMixed.NewBuilder().Build());
    }

    private static void Verify<T>(string kind, Func<T> create, Func<T> build) where T : InheritedManagedReferences
    {
        InheritedManagedDefaultsState.Reset();
        CheckRedeclaration(kind, create(), staged: false);
        InheritedManagedDefaultsState.Reset();
        CheckRedeclaration(kind, build(), staged: true);
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR");
        foreach (var format in new[] { VmfDocumentFormat.Json, VmfDocumentFormat.Yaml, VmfDocumentFormat.Xml })
        {
            foreach (bool references in new[] { false, true })
            {
                InheritedManagedDefaultsState.Reset();
                var value = create();
                value.Numbers.Clear();
                value.Numbers.Add(27);
                value.Settings.Clear();
                value.Settings.Put("wire", 31);
                value.Child = new InheritedManagedChild { Label = "shared" };
                if (references) value.Selection = value.Child;

                var omitted = SnapshotCollectionBehaviorTests.Omission(format, typeof(T).Name);
                var explicitFields = SnapshotCollectionBehaviorTests.Write(value, references, format);
                ReadBoth(omitted, explicitFields);
                if (directory is null) continue;

                string Filename(string author, string state) => Path.Combine(directory,
                    $"{author}-managed-redeclared-{kind}-{(references ? "refs" : "plain")}-{state}.{format.ToString().ToLowerInvariant()}");
                File.WriteAllText(Filename("csharp", "omitted"), omitted);
                File.WriteAllText(Filename("csharp", "supplied"), explicitFields);
                ReadBoth(File.ReadAllText(Filename("java", "omitted")), File.ReadAllText(Filename("java", "supplied")));

                void ReadBoth(string unset, string set)
                {
                    for (int attempt = 0; attempt < 2; ++attempt)
                    {
                        InheritedManagedDefaultsState.Reset();
                        CheckRedeclaration(kind, SnapshotCollectionBehaviorTests.Read<T>(unset, references, format), staged: true);
                        InheritedManagedDefaultsState.Reset();
                        var restored = SnapshotCollectionBehaviorTests.Read<T>(set, references, format);
                        CheckRedeclaration(kind, restored, staged: true, supplied: true);
                        Assert.Equal("shared", restored.Child?.Label);
                        Assert.Same(references ? restored.Child : null, restored.Selection);
                    }
                }
            }
        }
    }

    private static void CheckRedeclaration(string kind, InheritedManagedReferences value, bool staged, bool supplied = false)
    {
        bool boundNumbers = kind is "replacement" or "mixed";
        bool boundSettings = kind is "replacement";
        bool literal = kind is "empty";
        List<string> phases = [];
        if (staged) AddFactories(1);
        phases.Add("hook:references");
        AddFactories(staged ? 2 : 1);
        Assert.Equal(phases, InheritedManagedDefaultsState.Phases);

        int[] numbers = supplied ? [27] : boundNumbers ? [11] : staged || literal ? [] : [99];
        KeyValuePair<string, int?>[] settings = supplied ? [new("wire", 31)] : boundSettings
            ? [new("altered-1", 11)] : staged || literal ? [] : [new("seed-a", 1), new("seed-b", 2)];
        Assert.Equal(numbers, value.Numbers);
        Assert.Equal(settings, value.Settings.ToArray());
        Assert.Same(value, InheritedManagedDefaultsState.LastOwner);
        Assert.Same(value.Numbers, InheritedManagedDefaultsState.LastNumbers);
        Assert.Same(value.Settings, InheritedManagedDefaultsState.LastSettings);

        List<string> listEvents = [];
        string end = $"[{string.Join(", ", numbers)}]";
        if (boundNumbers)
        {
            listEvents.Add(staged ? "[99]>[12]" : "[99]>[11]");
            if (staged) listEvents.Add($"[12]>{end}");
        }
        else if (literal)
        {
            listEvents.Add("[99]>[]");
            if (supplied) listEvents.Add("[]>[27]");
        }
        else if (staged)
        {
            listEvents.Add($"[99]>{end}");
        }
        List<string> mapEvents = [];
        if (staged || boundSettings || literal) mapEvents.AddRange(["seed-a>", "seed-b>"]);
        if (boundSettings)
        {
            if (staged) mapEvents.AddRange([">altered-2", "altered-2>"]);
            mapEvents.Add(supplied ? ">wire" : ">altered-1");
        }
        else if (supplied)
        {
            mapEvents.Add(">wire");
        }
        Assert.Equal(listEvents, InheritedManagedDefaultsState.NumbersEdits);
        Assert.Equal(mapEvents, InheritedManagedDefaultsState.SettingsEdits);

        void AddFactories(int call)
        {
            if (boundNumbers) phases.Add($"alternative-numbers:{call}");
            if (boundSettings) phases.Add($"alternative-settings:{call}");
        }
    }
}
