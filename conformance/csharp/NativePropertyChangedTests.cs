using System.ComponentModel;
using Eu.Mihosoft.Vmf.Conformance.Hierarchy;
using Eu.Mihosoft.Vmf.Conformance.Opposites;
using Eu.Mihosoft.Vmf.Conformance.Values;
using Xunit;

namespace Vmf.CSharp.Conformance;

public sealed class NativePropertyChangedTests
{
    [Fact]
    public void NativeAndProjectedEventsUseThePublicNameAndTheirOwnSender()
    {
        var model = new Partner();
        var view = model.AsReadOnly();
        var writable = (INotifyPropertyChanged)model;
        var projected = (INotifyPropertyChanged)view;
        var actual = new List<string?>();
        var readOnly = new List<string?>();
        writable.PropertyChanged += (sender, args) =>
        {
            Assert.Same(model, sender);
            actual.Add(args.PropertyName);
        };
        projected.PropertyChanged += (sender, args) =>
        {
            Assert.Same(view, sender);
            readOnly.Add(args.PropertyName);
        };
        model.PartnerReference = new Partner();
        model.Name = "new";

        Assert.Equal([nameof(Partner.PartnerReference), nameof(Partner.Name)], actual);
        Assert.Equal(actual, readOnly);
    }

    [Fact]
    public void InheritedRoleMembersReportTheClrNameInsteadOfTheWireAlias()
    {
        var model = new CatalogItem();
        NamedItem primary = model;
        IRatedItem secondary = model;
        var view = model.AsReadOnly();
        var actual = new List<string?>();
        var readOnly = new List<string?>();
        ((INotifyPropertyChanged)primary).PropertyChanged += (_, args) => actual.Add(args.PropertyName);
        ((INotifyPropertyChanged)view).PropertyChanged += (_, args) => readOnly.Add(args.PropertyName);
        primary.Name = "label on wire";
        secondary.Rating = 7;
        model.Code = "derived";

        Assert.Equal([nameof(CatalogItem.Name), nameof(CatalogItem.Rating), nameof(CatalogItem.Code)], actual);
        Assert.Equal(actual, readOnly);
    }

    [Fact]
    public void ExplicitEventKeepsAModeledPropertyChangedMemberUsable()
    {
        var model = new EventNamedFields();
        var reported = new List<string?>();
        ((INotifyPropertyChanged)model).PropertyChanged += (_, args) => reported.Add(args.PropertyName);
        model.PropertyChanged = "modeled value";
        model.Event = "ordinary model keyword";

        Assert.Equal("modeled value", model.AsReadOnly().PropertyChanged);
        Assert.Equal([nameof(EventNamedFields.PropertyChanged), nameof(EventNamedFields.Event)], reported);
    }

    [Fact]
    public void CombinedDuplicateHandlersUseClrRemovalAndReleaseTheirRawRelay()
    {
        var model = new ArchiveNode();
        var notifier = (INotifyPropertyChanged)model;
        var trace = new List<string>();
        PropertyChangedEventHandler first = (_, _) => trace.Add("first");
        PropertyChangedEventHandler second = (_, _) => trace.Add("second");
        PropertyChangedEventHandler pair = first + second;
        using var before = model.VmfChanges.AddDirectPropertyListener(_ => trace.Add("raw before"));
        notifier.PropertyChanged += pair;
        notifier.PropertyChanged += first;
        using var after = model.VmfChanges.AddDirectPropertyListener(_ => trace.Add("raw after"));

        model.Name = "one";
        Assert.Equal(["raw before", "first", "second", "first", "raw after"], trace);
        trace.Clear();
        notifier.PropertyChanged -= first;
        model.Name = "two";
        Assert.Equal(["raw before", "first", "second", "raw after"], trace);
        trace.Clear();
        notifier.PropertyChanged -= pair;
        model.Name = "three";
        Assert.Equal(["raw before", "raw after"], trace);
        trace.Clear();
        notifier.PropertyChanged += second;
        model.Name = "four";
        Assert.Equal(["raw before", "raw after", "second"], trace);
        trace.Clear();

        model.Trail.Add(new ArchiveNode());
        model.Children.Add(new ArchiveNode());
        model.Children[0]!.Name = "recursive only";
        Assert.Empty(trace);
    }

    [Fact]
    public void MulticastDispatchKeepsTheInvocationSnapshot()
    {
        var model = new ArchiveNode();
        var notifier = (INotifyPropertyChanged)model;
        var trace = new List<string>();
        PropertyChangedEventHandler later = (_, _) => trace.Add("later");
        notifier.PropertyChanged += (_, _) =>
        {
            trace.Add("first");
            notifier.PropertyChanged -= later;
        };
        notifier.PropertyChanged += later;
        model.Name = "one";
        model.Name = "two";
        Assert.Equal(["first", "later", "first"], trace);
    }
}
