using Eu.Mihosoft.Vmf.Example.Workshops;
using Vmf.Runtime;

if (args.Length == 3 && args[0].StartsWith("snapshot-", StringComparison.Ordinal))
{
    WorkshopSnapshots.Run(args[0], args[1], args[2]);
    return;
}

var left = new Workshop();
var right = new Workshop();
var first = new Station { Name = "first" };
var second = new Station { Name = "second" };

Check(HasMultiplicityIssue(left.Validate(), "Stations"), "Empty containment fails lower-bound validation");
Check(left.Stations.Add(first), "Insert the first station");
Check(left.Stations.Add(second), "Insert the second station");
bool duplicate = left.Stations.Add(first);
Check(!duplicate && left.Stations.Count == 2, "Adding a station twice is a no-op");

Expect<ArgumentException>(() => left.Stations.Insert(0, first));
Expect<ArgumentNullException>(() => left.Stations.Add(null!));
Expect<ArgumentOutOfRangeException>(() => right.Stations.Insert(1, second));
Expect<ArgumentOutOfRangeException>(() => left.Stations.Move(0, 2));
Check(left.Stations.Count == 2 && right.Stations.Count == 0
    && ReferenceEquals(left.Stations[0], first) && ReferenceEquals(left.Stations[1], second)
    && ReferenceEquals(second.Workshop, left), "Rejected writes leave both sides and order intact");

left.Stations.Move(1, 0);
Check(ReferenceEquals(left.Stations[0], second) && ReferenceEquals(left.Stations[1], first),
    "Order changes without replacing either station");
string ordered = string.Join(",", left.Stations.Select(station => station.Name));

second.Name = first.Name;
Check(left.Favorites.Add(first) && left.Favorites.Add(second), "Distinct references may have equal values");
Check(!left.Favorites.Add(first) && left.Favorites.Count == 2, "Favorites are unique by identity");
Check(!left.Favorites.Contains(new Station { Name = first.Name }), "A lookalike station is not a member");
second.Name = "second";

first.Workshop = right;
string reparent = $"{left.Stations.Count},{right.Stations.Count}";
bool opposite = ReferenceEquals(first.Workshop, right) && ReferenceEquals(second.Workshop, left);
bool identity = ReferenceEquals(right.Stations[0], first) && ReferenceEquals(left.Stations[0], second);
bool favoriteAlias = left.Favorites.Contains(right.Stations[0]);
Check(reparent == "1,1" && opposite && identity && favoriteAlias,
    "Assigning the opposite moves the original station and retains its independent references");
Check(left.Validate().Count == 0 && right.Validate().Count == 0, "Both workshops are valid after reparenting");

left.Stations.Insert(0, new Station { Name = "third" });
left.Stations.Add(new Station { Name = "fourth" });
int overfull = left.Stations.Count;
Check(overfull == 3 && HasMultiplicityIssue(left.Validate(), "Stations"),
    "Reference collection upper bound is checked by validation after edits");

var unnamed = new Station();
right.Stations.Add(unnamed);
bool missingName = HasMultiplicityIssue(right.Validate(), "Stations[1].Name");
Check(missingName && HasMultiplicityIssue(unnamed.Validate(), "Name"),
    "Required name is validated on both the station and its containing workshop");
unnamed.Name = "";
Check(right.Validate().Count == 0, "An empty string counts as present");
unnamed.Workshop = null;
Check(unnamed.Workshop is null && !right.Stations.Contains(unnamed) && right.Stations.Count == 1,
    "Clearing the opposite detaches the station from its containment");
unnamed.Name = null;
Check(right.Favorites.Add(unnamed) && right.Validate().Count == 0,
    "Default validation follows containment only");

var records = new ReferenceSet<EqualValue>();
var equalFirst = new EqualValue("same");
var equalSecond = new EqualValue("same");
Check(equalFirst.Equals(equalSecond) && records.Add(equalFirst) && records.Add(equalSecond)
    && records.Remove(equalFirst) && records.Contains(equalSecond),
    "Reference sets use identity even when the item type overrides value equality");

string trace = $"ordered={ordered};duplicate={Flag(duplicate)};reparent={reparent};opposite={Flag(opposite)};"
    + $"identity={Flag(identity)};favoriteAlias={Flag(favoriteAlias)};overfull={overfull};missingName={Flag(missingName)}";
if (args.Length == 0)
{
    Console.WriteLine(trace);
}
else
{
    File.WriteAllText(args[0], trace);
}

static bool HasMultiplicityIssue(IReadOnlyList<ValidationIssue> issues, string path) =>
    issues.Any(issue => issue.Code == "multiplicity" && issue.Path == path);

static string Flag(bool value) => value ? "true" : "false";

static void Check(bool condition, string message)
{
    if (!condition)
    {
        throw new InvalidOperationException(message);
    }
}

static void Expect<TException>(Action action) where TException : Exception
{
    try
    {
        action();
    }
    catch (TException)
    {
        return;
    }

    throw new InvalidOperationException($"Expected {typeof(TException).Name}.");
}

internal sealed record EqualValue(string Text);
