using System.Text.Json;
using System.Text.Json.Nodes;
using Eu.Mihosoft.Vmf.Example.Workshops;

internal static class WorkshopSnapshots
{
    internal static void Run(string command, string snapshots, string fixtures)
    {
        switch (command)
        {
            case "snapshot-write":
                Directory.CreateDirectory(snapshots);
                var site = CreateSite();
                File.WriteAllText(Path.Combine(snapshots, "workshops.json"), ModelSnapshot.Write(site));
                File.WriteAllText(Path.Combine(snapshots, "catalog.json"), ModelSnapshot.Write(CreateCatalog()));
                var inventory = ModelSnapshot.ReadPlain<ExternalInventory>(Read(fixtures, "inventory.json"));
                File.WriteAllText(Path.Combine(snapshots, "inventory.json"), ModelSnapshot.WritePlain(inventory));
                Reject(() => ModelSnapshot.WritePlain(site), "live favorite in plain JSON");
                var polymorphic = CreateCatalog();
                polymorphic.Featured = null;
                Reject(() => ModelSnapshot.WritePlain(polymorphic), "subtype needing a discriminator in plain JSON");
                Reject(() => ModelSnapshot.Write(site.Workshops[0]), "detached favorite");
                break;
            case "snapshot-read":
                VerifySnapshots(snapshots);
                VerifySnapshots(fixtures);
                VerifyPlainSnapshot(snapshots, fixtures);
                VerifyInvalidSnapshots(fixtures);
                break;
            default:
                throw new ArgumentException($"Unknown snapshot command {command}.");
        }
    }

    private static WorkshopSite CreateSite()
    {
        var site = new WorkshopSite();
        var left = new Workshop();
        var right = new Workshop();
        site.Workshops.Add(left);
        site.Workshops.Add(right);
        var shared = new Station { Name = "shared" };
        left.Stations.Add(new Station { Name = "left" });
        left.Stations.Add(shared);
        left.Favorites.Add(shared);
        shared.Workshop = right;
        right.Stations.Add(new Station { Name = "right" });
        return site;
    }

    private static Catalog CreateCatalog()
    {
        var thermometer = new Thermometer { Sku = "T-1", Unit = "C" };
        var catalog = new Catalog { Featured = thermometer };
        catalog.Products.Add(thermometer);
        catalog.Products.Add(new Instrument { Sku = "I-1" });
        return catalog;
    }

    private static void VerifySnapshots(string directory)
    {
        var site = ModelSnapshot.Read<WorkshopSite>(Read(directory, "workshops.json"));
        var left = site.Workshops[0];
        var right = site.Workshops[1];
        var shared = right.Stations[0];
        Check(site.Workshops.Count == 2 && left.Stations.Count == 1 && left.Stations[0].Name == "left"
            && right.Stations.Count == 2 && shared.Name == "shared" && right.Stations[1].Name == "right",
            "Workshop containment order");
        Check(ReferenceEquals(left.Favorites.Single(), shared) && ReferenceEquals(shared.Workshop, right),
            "Workshop favorite identity and opposite link");
        Check(site.Validate().Count == 0, "Valid restored workshop graph");
        shared.Workshop = left;
        Check(ReferenceEquals(left.Stations[1], shared) && left.Favorites.Contains(shared) && right.Stations.Count == 1,
            "Reparenting a restored station");

        string catalogJson = Read(directory, "catalog.json");
        using var document = JsonDocument.Parse(catalogJson);
        var root = document.RootElement;
        var items = root.GetProperty("items");
        Check(!root.TryGetProperty("products", out _) && items[0].GetProperty("@vmf-type").GetString() == "thermometer"
            && items[1].GetProperty("@vmf-type").GetString() == "instrument", "Shared wire field name and subtype aliases");
        Shelf<Instrument> shelf = ModelSnapshot.Read<Catalog>(catalogJson);
        Check(shelf.Products.Count == 2 && shelf.Products[0] is Thermometer
            && shelf.Featured is Thermometer { Sku: "T-1", Unit: "C" }
            && shelf.Products[1] is { Sku: "I-1" } and not Thermometer
            && ReferenceEquals(shelf.Featured, shelf.Products[0]),
            "Generic containment, concrete subtype and featured reference identity");
    }

    private static void VerifyPlainSnapshot(string snapshots, string fixtures)
    {
        string json = Read(snapshots, "inventory.json");
        var external = JsonNode.Parse(Read(fixtures, "inventory.json"));
        Check(JsonNode.DeepEquals(external, JsonNode.Parse(json)), "Plain JSON keeps the external field names and shape");

        var inventory = ModelSnapshot.ReadPlain<ExternalInventory>(json);
        var first = inventory.Items[0];
        var second = inventory.Items[1];
        Check(inventory.WarehouseId == "BER-1" && inventory.Items.Count == 2
            && first.Sku == "A-42" && first.Quantity == 0 && !first.Active
            && second.Sku == "B-10" && second.Quantity == 8 && second.Active,
            "Plain JSON restores the typed inventory, including zero and false");
        Check(JsonNode.DeepEquals(external, JsonNode.Parse(ModelSnapshot.WritePlain(inventory))),
            "Plain JSON re-exports without VMF metadata");
        Reject(() => ModelSnapshot.ReadPlain<Catalog>(Read(fixtures, "catalog.json")),
            "VMF metadata in plain JSON");
    }

    private static void VerifyInvalidSnapshots(string fixtures)
    {
        string workshop = Read(fixtures, "workshops.json");
        Reject(() => ModelSnapshot.Read<WorkshopSite>(workshop.Replace("\"2\"", "\"missing\"")), "dangling reference");
        Reject(() => ModelSnapshot.Read<WorkshopSite>(workshop.Replace("[\"2\"]", "[\"2\", 2]")),
            "duplicate reference to one identity");
        Reject(() => ModelSnapshot.Read<WorkshopSite>(workshop.Replace("{\"name\": \"right\"}",
            "{\"@vmf-id\": \"2\", \"name\": \"right\"}")), "duplicate identity");
        Reject(() => ModelSnapshot.Read<WorkshopSite>(workshop.Replace("\"favorites\": [\"2\"]",
            "\"@vmf-id\": \"wrong\", \"favorites\": [\"wrong\"]")), "wrong reference type");
        string catalog = Read(fixtures, "catalog.json");
        Reject(() => ModelSnapshot.Read<Catalog>(catalog.Replace("\"thermometer\"", "\"unknown-subtype\"")),
            "unknown subtype alias");

        string legacyCatalog = catalog.Replace("\"thermometer\"",
            "\"eu.mihosoft.vmf.example.workshops.Thermometer\"");
        var legacy = ModelSnapshot.Read<Catalog>(legacyCatalog);
        Check(legacy.Featured is Thermometer && ReferenceEquals(legacy.Featured, legacy.Products[0]),
            "Legacy JSON with a qualified Java type name");
    }

    private static string Read(string directory, string file) => File.ReadAllText(Path.Combine(directory, file));

    private static void Reject(Action operation, string problem)
    {
        try
        {
            operation();
        }
        catch (JsonException)
        {
            return;
        }
        throw new InvalidOperationException($"Accepted invalid snapshot: {problem}");
    }

    private static void Check(bool condition, string problem)
    {
        if (!condition)
        {
            throw new InvalidOperationException(problem);
        }
    }
}
