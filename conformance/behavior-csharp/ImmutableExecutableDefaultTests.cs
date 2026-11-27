using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Conformance.Behavior;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ImmutableExecutableDefaultTests
{
    private static readonly string[] Staging = ["members", "label", "alpha", "zulu"];

    [Fact]
    public void ImmutableSnapshotStagesScalarAndReferenceDefaultsBeforeWireOverrides()
    {
        ImmutableDefaultTrace.Phases.Clear();
        Assert.NotNull(ModelSnapshot.TypeCatalog.Find(typeof(ImmutableExecutableDefaults)));
        Assert.Empty(ImmutableDefaultTrace.Phases);
        var builder = ImmutableExecutableDefaults.NewBuilder();
        var first = builder.Build();
        var second = builder.Build();
        Assert.Equal(Staging, ImmutableDefaultTrace.Phases);
        Assert.Same(Assert.Single(first.Members), Assert.Single(second.Members));

        ImmutableDefaultTrace.Phases.Clear();
        var reverse = ModelSnapshot.Read<ImmutableExecutableDefaults>(
            """
            {"witness":{"title":"wire-witness"},"selected":"member",
             "members":[{"@vmf-id":"member","label":"wire-member"}]}
            """);
        Assert.Same(Assert.Single(reverse.Members), reverse.Selected);
        Assert.Equal(["members", "label", "alpha", "zulu", "witness", "label"], ImmutableDefaultTrace.Phases);
    }

    [Fact]
    public void ImmutableSnapshotDefaultsExchangeAcrossProfilesWithNoExtraEvaluation()
    {
        string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR");
        foreach (var format in new[] { VmfDocumentFormat.Json, VmfDocumentFormat.Yaml, VmfDocumentFormat.Xml })
        {
            foreach (bool references in new[] { false, true })
            {
                foreach (string state in new[] { "omitted", "empty", "nested" })
                {
                    string document = Write(state, references, format);
                    VerifyDocument(document);
                    if (directory is null) continue;

                    string Filename(string author) => Path.Combine(directory,
                        $"{author}-immutable-executable-{(references ? "refs" : "plain")}-{state}.{format.ToString().ToLowerInvariant()}");
                    File.WriteAllText(Filename("csharp"), document);
                    VerifyDocument(File.ReadAllText(Filename("java")));

                    void VerifyDocument(string input)
                    {
                        for (int repeat = 0; repeat < 2; ++repeat)
                        {
                            Check(typedValues: false);
                            if (!references) Check(typedValues: true);
                        }
                        void Check(bool typedValues)
                        {
                            ImmutableDefaultTrace.Phases.Clear();
                            var restored = SnapshotCollectionBehaviorTests.Read<ImmutableExecutableDefaults>(
                                input, references, format, typedValues);
                            Verify(restored, state, references);
                        }
                    }
                }
            }
        }

        ImmutableDefaultTrace.NullAlpha = true;
        try
        {
            var value = ImmutableExecutableDefaults.NewBuilder().Build();
            ImmutableDefaultTrace.Phases.Clear();
            Assert.False(JsonNode.Parse(ModelSnapshot.Write(value))!.AsObject().ContainsKey("first"));
            Assert.Equal(["alpha"], ImmutableDefaultTrace.Phases);
        }
        finally
        {
            ImmutableDefaultTrace.NullAlpha = false;
        }
    }

    private static string Write(string state, bool references, VmfDocumentFormat format)
    {
        if (state == "omitted") return SnapshotCollectionBehaviorTests.Omission(format, nameof(ImmutableExecutableDefaults));
        ImmutableExecutableDefaults value;
        if (state == "empty")
        {
            value = ImmutableExecutableDefaults.NewBuilder().WithAlpha("alpha-wire").WithMembers([]).WithZulu(23).Build();
        }
        else
        {
            var child = ImmutableDefaultMember.NewBuilder().WithLabel("wire-member").Build();
            var witness = ImmutableDefaultWitness.NewBuilder().WithTitle("wire-witness").Build();
            value = ImmutableExecutableDefaults.NewBuilder().WithAlpha(null).WithMembers([child]).WithSelected(child)
                .WithWitness(witness).WithZulu(29).Build();
        }
        ImmutableDefaultTrace.Phases.Clear();
        var document = SnapshotCollectionBehaviorTests.Write(value, references, format);
        Assert.Equal(state == "nested" ? ["alpha"] : [], ImmutableDefaultTrace.Phases);
        return document;
    }

    private static void Verify(ImmutableExecutableDefaults value, string state, bool references)
    {
        var field = ModelSnapshot.Reflection.Reflect(value).FindProperty("members")!;
        var captured = Assert.Single(Assert.IsAssignableFrom<IReadOnlyList<ImmutableDefaultMember>>(field.DefaultValue));
        Assert.Equal("default-member", captured.Label);
        if (state == "nested")
        {
            Assert.Null(value.Alpha);
            Assert.Equal(29, value.Zulu);
            var member = Assert.Single(value.Members);
            Assert.Equal("wire-member", member.Label);
            Assert.Equal("wire-member", value.Selected?.Label);
            Assert.Equal(references, ReferenceEquals(member, value.Selected));
            Assert.Equal("wire-witness", value.Witness?.Title);
            Assert.Equal(references ? [..Staging, "label", "witness"] : [..Staging, "label", "label", "witness"],
                ImmutableDefaultTrace.Phases);
            return;
        }
        if (state == "empty")
        {
            Assert.Equal("alpha-wire", value.Alpha);
            Assert.Equal(23, value.Zulu);
            Assert.Empty(value.Members);
        }
        else
        {
            Assert.Equal("alpha-default", value.Alpha);
            Assert.Equal(19, value.Zulu);
            Assert.Same(captured, Assert.Single(value.Members));
        }
        Assert.Equal(Staging, ImmutableDefaultTrace.Phases);
    }
}
