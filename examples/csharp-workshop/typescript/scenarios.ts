import {
    Catalog, ExternalInventory, Instrument, ModelSnapshot, Shelf, Station, Thermometer,
    Workshop, WorkshopSite,
} from "./build/generated/eu/mihosoft/vmf/example/workshops/model.g.js";

export function writeSnapshots() {
    const site = new WorkshopSite();
    const [left, right] = [new Workshop(), new Workshop()];
    site.workshops.add(left);
    site.workshops.add(right);
    const shared = station("shared");
    left.stations.add(station("left"));
    left.stations.add(shared);
    left.favorites.add(shared);
    shared.workshop = right;
    right.stations.add(station("right"));
    check(right.stations.at(0) === shared && left.stations.count === 1 && left.favorites.contains(shared),
        "Native reparenting changes both collections and retains the reference");

    const thermometer = new Thermometer();
    thermometer.sku = "T-1";
    thermometer.unit = "C";
    const instrument = new Instrument();
    instrument.sku = "I-1";
    const catalog = new Catalog();
    catalog.products.add(thermometer);
    catalog.products.add(instrument);
    catalog.featured = thermometer;

    rejects(() => ModelSnapshot.write(left), "detached favorite");
    rejects(() => ModelSnapshot.writePlain(site, WorkshopSite), "live favorite in plain JSON");
    catalog.featured = null;
    rejects(() => ModelSnapshot.writePlain(catalog, Catalog), "subtype needing a discriminator");
    catalog.featured = thermometer;

    return { workshops: ModelSnapshot.write(site), catalog: ModelSnapshot.write(catalog) };
}

export function verifySnapshots(workshopJson: string, catalogJson: string): void {
    const site = ModelSnapshot.read(workshopJson, WorkshopSite);
    check(site.workshops.count === 2, "Workshop containment count");
    const left = at(site.workshops, 0);
    const right = at(site.workshops, 1);
    const shared = at(right.stations, 0);
    check(left.stations.count === 1 && at(left.stations, 0).name === "left"
        && right.stations.count === 2 && shared.name === "shared" && at(right.stations, 1).name === "right",
        "Workshop containment order");
    check(left.favorites.count === 1 && left.favorites.contains(shared) && shared.workshop === right,
        "Workshop favorite identity and opposite link");
    check(site.validate().length === 0, "Valid restored workshop graph");
    check(!right.stations.add(shared), "Adding the same object is a no-op");
    right.stations.move(0, 1);
    check(right.stations.at(1) === shared && shared.workshop === right, "Reordering preserves parent and identity");
    left.stations.add(shared);
    check(left.stations.at(1) === shared && shared.workshop === left && left.favorites.contains(shared)
        && right.stations.count === 1, "Reparenting from the containment end after loading");
    left.stations.add(station("third"));
    check(left.validate().length > 0, "Collection multiplicity remains active on loaded models");

    const wire = JSON.parse(catalogJson) as { items: Record<string, unknown>[]; products?: unknown };
    check(wire.products === undefined && wire.items[0]?.["@vmf-type"] === "thermometer"
        && wire.items[1]?.["@vmf-type"] === "instrument", "Shared wire field name and subtype aliases");
    const shelf: Shelf<Instrument> = ModelSnapshot.read(catalogJson, Catalog);
    const featured: Instrument | null = shelf.featured;
    check(shelf.products.count === 2 && at(shelf.products, 0) instanceof Thermometer
        && featured instanceof Thermometer && featured.sku === "T-1" && featured.unit === "C"
        && at(shelf.products, 1).sku === "I-1" && !(at(shelf.products, 1) instanceof Thermometer)
        && featured === at(shelf.products, 0), "Typed generic containment, concrete subtype and featured identity");
    // Keep this assertion compile-time only. The wrong type must remain a TypeScript error.
    const incompatible = () => {
        // @ts-expect-error A Workshop is not an Instrument.
        shelf.products.add(new Workshop());
    };
    void incompatible;
}

export function roundTripInventory(json: string): string {
    const inventory = ModelSnapshot.readPlain(json, ExternalInventory);
    check(inventory.warehouseId === "BER-1" && inventory.items.count === 2, "Plain JSON inventory shape");
    const first = at(inventory.items, 0);
    const second = at(inventory.items, 1);
    check(first.sku === "A-42" && first.quantity === 0 && first.active === false
        && second.sku === "B-10" && second.quantity === 8 && second.active,
        "Plain JSON restores typed values, including zero and false");
    return ModelSnapshot.writePlain(inventory, ExternalInventory);
}

export function verifyInvalidSnapshots(workshop: string, catalog: string): void {
    rejects(() => ModelSnapshot.read(workshop.replace('"2"', '"missing"'), WorkshopSite), "dangling reference");
    rejects(() => ModelSnapshot.read(workshop.replace('["2"]', '["2", 2]'), WorkshopSite), "duplicate reference");
    rejects(() => ModelSnapshot.read(workshop.replace('{"name": "right"}',
        '{"@vmf-id": "2", "name": "right"}'), WorkshopSite), "duplicate identity");
    rejects(() => ModelSnapshot.read(workshop.replace('"favorites": ["2"]',
        '"@vmf-id": "wrong", "favorites": ["wrong"]'), WorkshopSite), "wrong reference type");
    rejects(() => ModelSnapshot.read(catalog.replace('"thermometer"', '"unknown-subtype"'), Catalog),
        "unknown subtype alias");
    rejects(() => ModelSnapshot.readPlain(catalog, Catalog), "VMF metadata in plain JSON");
    rejects(() => ModelSnapshot.read(workshop.replace('"name": "right"', '"name": "right", "name": "dup"'),
        WorkshopSite), "duplicate JSON field name");

    const legacy = ModelSnapshot.read(catalog.replace('"thermometer"',
        '"eu.mihosoft.vmf.example.workshops.Thermometer"'), Catalog);
    check(legacy.featured instanceof Thermometer && legacy.featured === legacy.products.at(0),
        "Legacy JSON with a qualified Java type name");

    const large = ModelSnapshot.read(workshop.replace('"2"', '"9007199254740993"')
        .replace('"@vmf-id": 2', '"@vmf-id": 9007199254740993')
        .replace('{"name": "right"}', '{"@vmf-id": 9007199254740992, "name": "right"}'), WorkshopSite);
    const target = at(at(large.workshops, 1).stations, 0);
    check(at(large.workshops, 0).favorites.contains(target), "Large integer wire IDs retain distinct identities");
}

function at<T>(values: { at(index: number): T | undefined }, index: number): T {
    const value = values.at(index);
    if (value === undefined) throw new Error(`Missing collection item ${index}`);
    return value;
}

function station(name: string): Station {
    const result = new Station();
    result.name = name;
    return result;
}

function rejects(operation: () => unknown, problem: string): void {
    try {
        operation();
    } catch (error) {
        check(error instanceof Error && error.message.length > 0, `Missing diagnostic for ${problem}`);
        return;
    }
    throw new Error(`Accepted invalid data: ${problem}`);
}

function check(condition: unknown, problem: string): void {
    if (!condition) throw new Error(problem);
}
