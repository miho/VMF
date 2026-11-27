using Eu.Mihosoft.Vmf.Conformance.Ordinarycontainment;
using Vmf.Runtime;
using Xunit;

namespace Vmf.CSharp.Conformance;

public class OwningValidationTests
{
    [Fact]
    public void ContainedAliasesValidateOnceAtTheFirstLogicalPath()
    {
        var shared = InvalidCard();
        var separate = InvalidCard();
        separate.Weight = 1;
        var layout = new ValidationLayout();
        layout.Entries.AddRange([null, shared, null, separate]);
        layout.Entries[2] = shared;
        separate.Weight = 0;
        Assert.Same(shared, layout.Entries[1]);
        Assert.Same(shared, layout.Entries[2]);
        Assert.Same(separate, layout.Entries[3]);
        Assert.NotSame(shared, separate);
        Assert.Equal(shared, separate);

        string[] expected = ["vmf.multiplicity|/entries/1/labels", "vmf.multiplicity|/entries/1/title",
            "vmf.range|/entries/1/weight", "vmf.multiplicity|/entries/3/labels",
            "vmf.multiplicity|/entries/3/title", "vmf.range|/entries/3/weight"];
        var view = layout.AsReadOnly();
        var validator = ModelValidator.NewBuilder(ModelSnapshot.Reflection).Build();
        foreach (var model in new object[] { layout, view })
        {
            var report = validator.Validate(model);
            Assert.Equal(expected, report.Issues.Select(issue => $"{issue.Code}|{issue.Path}"));
            Assert.All(report.Issues, issue => Assert.Equal(ValidationSeverity.Error, issue.Severity));
            Assert.Equal(2, report.Issues[0].Property!.LowerBound);
            Assert.Equal(4, report.Issues[0].Property!.UpperBound);
            Assert.Equal(1, report.Issues[0].MultiplicityViolation!.ActualCount);
            Assert.Equal("display_title", report.Issues[1].Property!.WireName);
        }
        Assert.Same(view.Entries[1], validator.Validate(view).Issues[0].Object);

        foreach (var issues in new[] { layout.Validate(), view.Validate() })
        {
            Assert.Equal(expected, issues.Select(issue => $"{issue.ModelCode}|{issue.ModelPath}"));
            Assert.Equal("Entries[1].Labels", issues[0].Path);
        }

        shared.Labels.Add("two");
        shared.Title = "shared";
        shared.Weight = 2;
        Assert.Equal(expected[3..], view.Validate().Select(issue => $"{issue.ModelCode}|{issue.ModelPath}"));
    }

    private static ValidationCard InvalidCard()
    {
        var card = new ValidationCard { Weight = 0 };
        card.Labels.Add("one");
        return card;
    }
}
