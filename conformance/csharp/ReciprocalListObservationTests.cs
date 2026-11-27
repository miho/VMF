using Eu.Mihosoft.Vmftest.Referenceends;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class ReciprocalListObservationTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void OrdersSourceRelaysAndRecordsOnlyPrimaryEdits(bool viaOpposite)
    {
        var member = new Member();
        var team = new Team();
        var teams = member.Teams;
        var changes = member.VmfChanges;
        var trace = new List<string>();
        using var direct = teams.AddListener(_ => trace.Add(team.Members.Contains(member) ? "linked" : "unlinked"));
        using var projected = member.AsReadOnly().Teams.AddListener(_ => trace.Add("projected"));
        using var local = changes.AddLocalListener(_ => trace.Add($"local:{changes.History.Count}"));
        using var opposite = team.VmfChanges.AddLocalListener(_ => trace.Add("opposite"));
        using var later = teams.AddListener(_ => trace.Add($"late:{changes.History.Count}"));
        using var recursive = changes.AddRecursiveListener(_ => trace.Add($"recursive:{changes.History.Count}"));
        changes.Start();
        if (viaOpposite) team.Members.Add(member);
        else teams.Add(team);
        changes.Stop();

        Assert.Equal(viaOpposite
            ? ["linked", "projected", "local:0", "late:0", "recursive:0", "opposite"]
            : new[] { "opposite", "linked", "projected", "local:0", "late:1", "recursive:1" }, trace);
        Assert.Equal(viaOpposite ? 0 : 1, changes.History.Count);
        Assert.Same(team, Assert.Single(teams));
    }

    [Theory]
    [InlineData("none", new[] { "inverse" })]
    [InlineData("direct", new[] { "inverse", "old-direct" })]
    [InlineData("dormant", new[] { "inverse", "projected" })]
    [InlineData("model", new[] { "inverse", "old-model", "new-model" })]
    [InlineData("replace", new[] { "inverse", "old-direct" })]
    public void CapturesSourceRelaysBeforeInverseCallbacks(string kind, string[] expected)
    {
        var member = new Member();
        var team = new Team();
        var teams = member.Teams;
        var trace = new List<string>();
        using var first = kind switch
        {
            "direct" or "replace" => teams.AddListener(_ => trace.Add("old-direct")),
            "model" => member.VmfChanges.AddLocalListener(_ => trace.Add("old-model")),
            _ => null,
        };
        if (kind == "dormant") _ = member.AsReadOnly().Teams;
        var added = new List<IDisposable>();
        using var inverse = team.VmfChanges.AddLocalListener(_ =>
        {
            trace.Add("inverse");
            if (kind == "replace") first!.Dispose();
            added.Add(teams.AddListener(_ => trace.Add("new-direct")));
            added.Add(member.VmfChanges.AddLocalListener(_ => trace.Add("new-model")));
            added.Add(member.AsReadOnly().Teams.AddListener(_ => trace.Add("projected")));
        });
        try
        {
            teams.Add(team);
            Assert.Equal(expected, trace);
            trace.Clear();
            teams.Add(new Team());
            Assert.Contains("new-direct", trace);
            Assert.Contains("new-model", trace);
            Assert.Contains("projected", trace);
        }
        finally
        {
            foreach (var subscription in added) subscription.Dispose();
        }
    }
}
