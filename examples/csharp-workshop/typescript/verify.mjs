import assert from "node:assert/strict";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
    roundTripInventory, verifyInvalidSnapshots, verifySnapshots, writeSnapshots,
} from "./build/js/scenarios.js";

const [command, fixtures, ...directories] = process.argv.slice(2);
const read = (directory, file) => readFileSync(join(directory, file), "utf8");
const externalInventory = read(fixtures, "inventory.json");

switch (command) {
    case "write": {
        const [directory] = directories;
        mkdirSync(directory, { recursive: true });
        const payload = writeSnapshots();
        assert.deepEqual(payload, writeSnapshots(), "TypeScript snapshots are deterministic");
        for (const [name, json] of Object.entries(payload)) {
            writeFileSync(join(directory, `${name}.json`), json);
        }
        writeFileSync(join(directory, "inventory.json"), roundTripInventory(externalInventory));
        break;
    }
    case "read": {
        for (const directory of [fixtures, ...directories]) {
            verifySnapshots(read(directory, "workshops.json"), read(directory, "catalog.json"));
            const inventory = read(directory, "inventory.json");
            assert.deepEqual(JSON.parse(inventory), JSON.parse(externalInventory), "Plain external JSON shape");
            assert.deepEqual(JSON.parse(roundTripInventory(inventory)), JSON.parse(externalInventory),
                "Plain JSON re-exports without VMF metadata");
        }
        verifyInvalidSnapshots(read(fixtures, "workshops.json"), read(fixtures, "catalog.json"));
        break;
    }
    default:
        throw new Error(`Unknown conformance command ${command}`);
}
