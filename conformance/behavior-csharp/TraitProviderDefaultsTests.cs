using Eu.Mihosoft.Vmf.Conformance.Behavior;
using System.Text.Json.Nodes;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance
{
    public sealed class TraitProviderDefaultsTests
    {
        [Fact]
        public void ImmutableBuildersShareAGetterRoleProviderAndKeepReflectionLazy()
        {
            TraitDefaultLabel.Calls = 0;
            Assert.NotNull(ModelSnapshot.TypeCatalog.Find(typeof(DefaultedTraitValue))?.FindProperty("displayLabel"));
            Assert.Equal(0, TraitDefaultLabel.Calls);

            var builder = DefaultedTraitValue.NewBuilder();
            Assert.Equal(1, TraitDefaultLabel.Calls);
            Assert.Equal("shared-1", builder.Build().DisplayLabel);
            Assert.Equal("shared-1", builder.Build().DisplayLabel);
            Assert.Equal(1, TraitDefaultLabel.Calls);

            var ranked = RankedTraitValue.NewBuilder().WithRank(3).Build();
            Assert.Equal(3, ranked.Rank);
            Assert.Equal("shared-2", ((IReadOnlyTraitDefaultLabel)ranked).DisplayLabel);
            var reflected = ModelSnapshot.Reflection.Reflect(ranked).FindProperty("displayLabel")!;
            Assert.Equal("shared-3", reflected.DefaultValue);
            Assert.Equal("shared-4", reflected.DefaultValue);
            Assert.Equal("local", LiteralTraitValue.NewBuilder().Build().DisplayLabel);
            Assert.Null(ModelSnapshot.Reflection.Reflect(ClearedTraitValue.NewBuilder().Build()).FindProperty("displayLabel")!.DefaultValue);
            Assert.Equal(4, TraitDefaultLabel.Calls);

            Assert.Equal("shared-5", new DefaultedTraitValue().DisplayLabel);
            Assert.Null(new DefaultedTraitValue((string?)null).DisplayLabel);
            Assert.Equal(5, TraitDefaultLabel.Calls);
        }

        [Fact]
        public void GetterRoleDefaultsStageOnEveryImportAndOnlyAffectNullWrites()
        {
            string? directory = Environment.GetEnvironmentVariable("VMF_CONFORMANCE_BEHAVIOR");
            foreach (var format in new[] { VmfDocumentFormat.Json, VmfDocumentFormat.Yaml, VmfDocumentFormat.Xml })
            {
                foreach (bool references in new[] { false, true })
                {
                    foreach (string state in new[] { "omitted", "supplied", "cleared" })
                    {
                        string? expected = state == "supplied" ? "explicit" : null;
                        string contents;
                        if (state == "omitted")
                        {
                            contents = SnapshotCollectionBehaviorTests.Omission(format, nameof(DefaultedTraitValue));
                        }
                        else
                        {
                            TraitDefaultLabel.Calls = 0;
                            var value = DefaultedTraitValue.NewBuilder().WithDisplayLabel(expected).Build();
                            contents = SnapshotCollectionBehaviorTests.Write(value, references, format);
                            Assert.Equal(expected is null ? 2 : 1, TraitDefaultLabel.Calls);
                        }
                        string? wanted = state == "omitted" ? "shared-1" : expected;
                        Verify(contents);
                        if (directory is null) continue;

                        string Filename(string author) => Path.Combine(directory,
                            $"{author}-trait-provider-{(references ? "refs" : "plain")}-{state}.{format.ToString().ToLowerInvariant()}");
                        File.WriteAllText(Filename("csharp"), contents);
                        Verify(File.ReadAllText(Filename("java")));

                        void Verify(string document)
                        {
                            for (int repeat = 0; repeat < 2; ++repeat)
                            {
                                Check(typedValues: false);
                                if (!references) Check(typedValues: true);
                            }
                            void Check(bool typedValues)
                            {
                                TraitDefaultLabel.Calls = 0;
                                var restored = SnapshotCollectionBehaviorTests.Read<DefaultedTraitValue>(
                                    document, references, format, typedValues);
                                Assert.Equal(wanted, restored.DisplayLabel);
                                Assert.Equal(1, TraitDefaultLabel.Calls);
                            }
                        }
                    }
                }
            }

            TraitDefaultLabel.Calls = 0;
            Assert.Equal("local", ModelSnapshot.ReadPlain<LiteralTraitValue>("{}").DisplayLabel);
            Assert.Null(ModelSnapshot.ReadPlain<ClearedTraitValue>("{}").DisplayLabel);
            Assert.Equal(0, TraitDefaultLabel.Calls);

            TraitDefaultLabel.ReturnsNull = true;
            try
            {
                var value = DefaultedTraitValue.NewBuilder().Build();
                Assert.False(JsonNode.Parse(ModelSnapshot.WritePlain(value))!.AsObject().ContainsKey("label"));
                Assert.Equal(2, TraitDefaultLabel.Calls);
            }
            finally
            {
                TraitDefaultLabel.ReturnsNull = false;
            }
        }
    }
}

namespace Eu.Mihosoft.Vmf.Conformance.Behavior
{
    public abstract partial class TraitDefaultLabel
    {
        internal static int Calls { get; set; }
        internal static bool ReturnsNull { get; set; }

        private static partial string? CreateDefaultDisplayLabel()
        {
            int call = ++Calls;
            return ReturnsNull ? null : $"shared-{call}";
        }
    }
}
