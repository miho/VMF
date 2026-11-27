// Runs the portable VMF scenarios (see conformance/portable/README.md) against the generated TypeScript code,
// compiled to build/js by tsconfig.json.
// Usage: node runner.mjs <scenarios-dir> <unsupported-models> <expected-failures> <results.json> [filter]
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import * as vmf from "@vmf/runtime";

const TARGET = "typescript";
const CAPABILITIES = new Set(["core", "views", "events", "history", "reflection", "identity-equality", "json"]);
/** Modules that do not apply to the language, with the reason. */
const NOT_APPLICABLE = new Map([
    ["equality-modes", "JavaScript compares objects by identity only (===, Map, Set); TypeScript follows N3"],
]);
const OPERATIONS = ["new", "set", "add", "insert", "remove", "clear", "check", "let", "equal", "notEqual",
    "nativeEqual", "nativeNotEqual", "same", "notSame", "copy", "readOnly", "instanceOf", "notInstanceOf",
    "listen", "unlisten", "events", "record", "recorded", "undoAll", "redoAll", "properties", "annotations", "write",
    "read", "document", "calls"];
const generated = join(dirname(fileURLToPath(import.meta.url)), "build", "js", "build", "generated", "eu", "mihosoft",
    "vmf", "portable");

/** A scenario expectation that did not hold (as opposed to an edit the model rejected). */
class ScenarioFailure extends Error {
}

const modules = new Map();

async function loadModel(model) {
    let module = modules.get(model);
    if (module === undefined) {
        module = await import(pathToFileURL(join(generated, model, "model.g.js")).href);
        modules.set(model, module);
    }
    return module;
}

async function main() {
    const [scenarioDir, unsupportedFile, expectedFile, resultsFile, filter = ""] = process.argv.slice(2);
    const unsupported = readUnsupported(unsupportedFile);
    const expectedFailures = readExpectedFailures(expectedFile);
    const results = [];
    const ids = new Set();
    for (const file of readdirSync(scenarioDir).filter(name => name.endsWith(".json")).sort()) {
        const root = parseJson(readFileSync(join(scenarioDir, file), "utf8"));
        const stem = file.replace(/\.json$/, "");
        for (const scenario of root.scenarios) {
            const id = `${stem}/${scenario.id}`;
            if (ids.has(id)) {
                throw new Error(`Duplicate scenario id ${id}`);
            }
            ids.add(id);
            if (filter !== "" && !id.includes(filter)) {
                continue;
            }
            const model = scenario.model ?? root.model;
            const requires = scenario.requires ?? [];
            const result = { id, module: requires.length === 0 ? "core" : requires.join("+"), status: "", message: "" };
            const inapplicable = requires.find(module => NOT_APPLICABLE.has(module));
            if (inapplicable !== undefined) {
                result.status = "skipped";
                result.message = `${inapplicable} does not apply: ${NOT_APPLICABLE.get(inapplicable)}`;
            } else if (!requires.every(module => CAPABILITIES.has(module))) {
                result.status = "skipped";
                result.message = `target does not claim [${requires.join(", ")}]`;
            } else if (unsupported.has(model)) {
                result.status = "failed";
                result.message = `model '${model}' was not generated: ${unsupported.get(model)}`;
            } else {
                try {
                    new Execution(await loadModel(model)).run(scenario.steps);
                    result.status = "passed";
                } catch (error) {
                    result.status = "failed";
                    result.message = describe(error);
                }
            }
            results.push(result);
        }
    }
    process.exitCode = report(results, expectedFailures, resultsFile) ? 0 : 1;
}

// ---------------------------------------------------------------------------------------------------- reporting

function report(results, expectedFailures, file) {
    const unexpectedFailures = [];
    const unexpectedPasses = [];
    const modulesByName = new Map();
    let passed = 0;
    let failed = 0;
    let expected = 0;
    let skipped = 0;
    for (const result of results) {
        const counts = modulesByName.get(result.module) ?? [0, 0, 0];
        modulesByName.set(result.module, counts);
        const known = expectedFailures.has(result.id);
        if (result.status === "passed") {
            passed++;
            counts[0]++;
            if (known) {
                unexpectedPasses.push(result.id);
            }
        } else if (result.status === "failed") {
            failed++;
            counts[1]++;
            if (known) {
                expected++;
            } else {
                unexpectedFailures.push(`${result.id}: ${result.message}`);
            }
        } else {
            skipped++;
            counts[2]++;
        }
    }
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify(results.map(result => ({
        id: result.id, module: result.module, status: result.status,
        expectedFailure: expectedFailures.has(result.id), message: result.message,
    })), null, 2));

    for (const result of results) {
        if (result.status === "failed") {
            console.log(`  FAIL ${result.id}${expectedFailures.has(result.id) ? " (expected)" : ""}: ${result.message}`);
        }
    }
    console.log();
    console.log(`${TARGET} portable scenarios by module (passed/failed/skipped):`);
    for (const [module, counts] of [...modulesByName].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)) {
        console.log(`  ${module}: ${counts[0]}/${counts[1]}/${counts[2]}`);
    }
    console.log(`${TARGET}: ${passed} of ${passed + failed} passed, ${failed} failed (${expected} expected), ${skipped} skipped`);
    for (const id of unexpectedPasses) {
        console.log(`UNEXPECTED PASS (remove from expected failures): ${id}`);
    }
    for (const failure of unexpectedFailures) {
        console.log(`UNEXPECTED FAILURE: ${failure}`);
    }
    return unexpectedFailures.length === 0 && unexpectedPasses.length === 0;
}

function readUnsupported(file) {
    const models = new Map();
    if (existsSync(file)) {
        for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
            const tab = line.indexOf("\t");
            if (tab > 0) {
                models.set(line.substring(0, tab), line.substring(tab + 1));
            }
        }
    }
    return models;
}

function readExpectedFailures(file) {
    const failures = new Map();
    if (existsSync(file)) {
        for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
            const trimmed = line.trim();
            if (trimmed === "" || trimmed.startsWith("#")) {
                continue;
            }
            const comment = trimmed.indexOf("#");
            failures.set((comment < 0 ? trimmed : trimmed.substring(0, comment)).trim(),
                comment < 0 ? "" : trimmed.substring(comment + 1).trim());
        }
    }
    return failures;
}

function describe(error) {
    const message = error instanceof ScenarioFailure ? error.message
        : error instanceof Error ? `${error.name}: ${error.message}` : String(error);
    return message.replace(/\s+/g, " ");
}

/** Parses JSON and keeps integers beyond 2^53 exact as bigint. */
function parseJson(text) {
    return JSON.parse(text, (_key, value, context) =>
        typeof value === "number" && Number.isInteger(value) && !Number.isSafeInteger(value)
            && context?.source !== undefined && /^-?[0-9]+$/.test(context.source) ? BigInt(context.source) : value);
}

function has(node, key) {
    return typeof node === "object" && node !== null && Object.hasOwn(node, key);
}

function bigintJson(_key, value) {
    return typeof value === "bigint" ? value.toString() : value;
}

// ---------------------------------------------------------------------------------------------------- values

/** Model objects and immutable values; read-only views are not models themselves. */
function isModel(value) {
    if (typeof value !== "object" || value === null || vmf.isReadOnlyView(value)) {
        return false;
    }
    try {
        vmf.modelTypeOf(value);
        return true;
    } catch {
        return false;
    }
}

/** JavaScript's own equality: === and the SameValueZero of Map and Set, which must agree. */
function nativelyEqual(left, right) {
    const equal = left === right;
    if (equal !== (new Set([left, right]).size === 1)) {
        throw new ScenarioFailure("=== and Set disagree");
    }
    return equal;
}

function isCollection(value) {
    return value instanceof vmf.ContainmentList || value instanceof vmf.ScalarList || value instanceof vmf.ReferenceList
        || value instanceof vmf.ReferenceSet || isReadOnlyCollection(value);
}

function isReadOnlyCollection(value) {
    return value instanceof vmf.ReadOnlyList || value instanceof vmf.ReadOnlySet;
}

/** A read-only list has no edit operations: an edit through it is rejected (it does not exist in the API). */
function requireEditable(list, path) {
    if (isReadOnlyCollection(list)) {
        throw new TypeError(`${path} is read-only`);
    }
}

function sameScalar(actual, expected) {
    if (expected === null || expected === undefined) {
        return actual === null;
    }
    if (typeof expected === "number" || typeof expected === "bigint") {
        if (typeof actual === "bigint") {
            return typeof expected === "bigint" ? actual === expected
                : Number.isInteger(expected) && actual === BigInt(expected);
        }
        if (typeof actual === "number") {
            return typeof expected === "bigint" ? Number.isInteger(actual) && BigInt(actual) === expected
                : actual === expected;
        }
        return false;
    }
    if (typeof expected === "boolean" || typeof expected === "string") {
        return actual === expected;
    }
    if (Array.isArray(expected)) {
        const items = isCollection(actual) ? [...actual] : Array.isArray(actual) ? actual : undefined;
        return items !== undefined && items.length === expected.length
            && items.every((item, index) => sameScalar(item, expected[index]));
    }
    return false;
}

/**
 * Normalizes the events of one step: consecutive list events of the same kind on the same property are merged
 * (bulk edits), and a chain of set events on the same property (a -> b, b -> c) becomes one event (a -> c).
 */
function merged(received) {
    const result = [];
    for (const event of received) {
        const last = result.at(-1);
        const sameTarget = last !== undefined && last.step === event.step && last.source === event.source
            && last.property === event.property && last.kind === event.kind;
        if (sameTarget && (event.kind === "add" || event.kind === "remove")) {
            result[result.length - 1] = { ...last, elements: [...last.elements, ...event.elements],
                indices: [...last.indices, ...event.indices] };
        } else if (sameTarget && event.kind === "set" && Object.is(last.newValue, event.oldValue)) {
            result[result.length - 1] = { ...last, newValue: event.newValue };
        } else {
            result.push(event);
        }
    }
    return result;
}

/** JSON text that keeps bigint values exact. */
function stringifyJson(value) {
    if (typeof value === "bigint") {
        return value.toString();
    }
    if (Array.isArray(value)) {
        return `[${value.map(stringifyJson).join(",")}]`;
    }
    if (typeof value === "object" && value !== null) {
        return `{${Object.entries(value).map(([key, member]) => `${JSON.stringify(key)}:${stringifyJson(member)}`).join(",")}}`;
    }
    return JSON.stringify(value);
}

/** JSON equality: objects ignore key order, numbers compare by value. */
function sameJson(actual, expected) {
    const numeric = value => typeof value === "number" || typeof value === "bigint";
    if (numeric(expected) || numeric(actual)) {
        if (!numeric(expected) || !numeric(actual)) {
            return false;
        }
        if (typeof expected === "bigint" || typeof actual === "bigint") {
            return Number.isInteger(Number(expected)) && Number.isInteger(Number(actual))
                && BigInt(expected) === BigInt(actual);
        }
        return expected === actual;
    }
    if (Array.isArray(expected)) {
        return Array.isArray(actual) && actual.length === expected.length
            && expected.every((item, index) => sameJson(actual[index], item));
    }
    if (typeof expected === "object" && expected !== null) {
        if (typeof actual !== "object" || actual === null || Array.isArray(actual)) {
            return false;
        }
        const keys = Object.keys(expected);
        return keys.length === Object.keys(actual).length
            && keys.every(key => Object.hasOwn(actual, key) && sameJson(actual[key], expected[key]));
    }
    return actual === expected;
}

function sameObjects(actual, expected, unordered) {
    if (actual.length !== expected.length) {
        return false;
    }
    if (!unordered) {
        return actual.every((item, index) => item === expected[index]);
    }
    const remaining = [...expected];
    for (const item of actual) {
        const index = remaining.indexOf(item);
        if (index < 0) {
            return false;
        }
        remaining.splice(index, 1);
    }
    return true;
}

/** Splits `a.b[2].c` into [name, index] pairs. */
function parse(path) {
    return path.split(".").map(part => {
        const bracket = part.indexOf("[");
        return bracket < 0 ? [part, undefined] : [part.substring(0, bracket), Number(part.substring(bracket + 1, part.length - 1))];
    });
}

function operationOf(node) {
    const operation = Object.keys(node).find(key => OPERATIONS.includes(key));
    if (operation === undefined) {
        throw new ScenarioFailure(`unknown step ${JSON.stringify(node, bigintJson)}`);
    }
    return operation;
}

// ---------------------------------------------------------------------------------------------------- execution

class Execution {
    labels = new Map();
    events = new Map();
    /** Shared call logs: which listener received which event, in delivery order. */
    calls = new Map();
    subscriptions = new Map();
    undone = new Map();
    documents = new Map();
    step = 0;

    constructor(module) {
        this.module = module;
        this.registry = module.ModelSnapshot.registry;
    }

    run(steps) {
        for (const node of steps) {
            this.step++;
            const operation = operationOf(node);
            try {
                if (node.fails === true) {
                    this.expectRejection(operation, node);
                } else {
                    this.execute(operation, node);
                }
            } catch (error) {
                if (error instanceof ScenarioFailure) {
                    throw new ScenarioFailure(`step ${this.step} (${operation}): ${error.message}`);
                }
                throw new ScenarioFailure(`step ${this.step} (${operation}) threw ${describe(error)}`);
            }
        }
    }

    expectRejection(operation, node) {
        const before = this.fingerprint();
        try {
            this.execute(operation, node);
        } catch (error) {
            if (error instanceof ScenarioFailure) {
                throw error;
            }
            const after = this.fingerprint();
            if (before !== after) {
                throw new ScenarioFailure(`the rejected edit changed the model:\n before ${before}\n after  ${after}`);
            }
            return;
        }
        throw new ScenarioFailure("expected the edit to be rejected, but it succeeded");
    }

    execute(operation, node) {
        switch (operation) {
            case "new":
                this.labels.set(node.new, this.create(node.type, node.with));
                break;
            case "set":
                this.set(node.set, node);
                break;
            case "add":
                this.add(node.add, node, -1);
                break;
            case "insert":
                this.add(node.insert, node, node.at);
                break;
            case "remove":
                this.remove(node.remove, node);
                break;
            case "clear": {
                const list = this.list(node.clear);
                requireEditable(list, node.clear);
                list.clear();
                break;
            }
            case "check":
                this.check(node.check, node);
                break;
            case "let":
                this.labels.set(node.let, this.resolve(node.path));
                break;
            case "equal":
                this.pairs(node.equal, (left, right) => {
                    const a = this.content(left);
                    const b = this.content(right);
                    if (!a.contentEquals(right) || !b.contentEquals(left)) {
                        throw new ScenarioFailure("expected content equality");
                    }
                    if (a.contentHashCode() !== b.contentHashCode()) {
                        throw new ScenarioFailure("content-equal objects have different content hash codes");
                    }
                });
                break;
            case "notEqual":
                this.pairs(node.notEqual, (left, right) => {
                    if (this.content(left).contentEquals(right) || this.content(right).contentEquals(left)) {
                        throw new ScenarioFailure("expected content inequality");
                    }
                });
                break;
            case "nativeEqual":
                this.pairs(node.nativeEqual, (left, right) => {
                    if (!nativelyEqual(left, right)) {
                        throw new ScenarioFailure("expected native equality");
                    }
                });
                break;
            case "nativeNotEqual":
                this.pairs(node.nativeNotEqual, (left, right) => {
                    if (nativelyEqual(left, right)) {
                        throw new ScenarioFailure("expected native inequality");
                    }
                });
                break;
            case "same":
                this.pairs(node.same, (left, right) => {
                    if (left !== right) {
                        throw new ScenarioFailure("expected the same object");
                    }
                });
                break;
            case "notSame":
                this.pairs(node.notSame, (left, right) => {
                    if (left === right) {
                        throw new ScenarioFailure("expected different objects");
                    }
                });
                break;
            case "copy":
                this.labels.set(node.as, this.content(this.resolve(node.copy)).deepCopy());
                break;
            case "readOnly": {
                const model = this.content(this.resolve(node.readOnly));
                this.labels.set(node.as, model.asReadOnly());
                break;
            }
            case "properties": {
                const names = this.reflect(node.properties).properties.map(property => property.name);
                if (names.length !== node.are.length || names.some((name, index) => name !== node.are[index])) {
                    throw new ScenarioFailure(`expected properties ${JSON.stringify(node.are)}, found ${JSON.stringify(names)}`);
                }
                break;
            }
            case "annotations":
                this.checkAnnotations(node.annotations, node.are);
                break;
            case "instanceOf":
            case "notInstanceOf": {
                const type = this.module[node.type];
                if (typeof type !== "function") {
                    throw new ScenarioFailure(`unknown type ${node.type}`);
                }
                const instance = this.resolve(node[operation]) instanceof type;
                if (instance !== (operation === "instanceOf")) {
                    throw new ScenarioFailure(`expected ${node[operation]}${instance ? " not " : " "}to be a ${node.type}`);
                }
                break;
            }
            case "listen":
                this.listen(node.listen, node.as, node.scope ?? "recursive", node);
                break;
            case "calls":
                this.checkCalls(node.calls, node.are);
                break;
            case "unlisten":
                this.subscriptions.get(node.unlisten)();
                this.subscriptions.delete(node.unlisten);
                break;
            case "events":
                this.checkEvents(node.events, node);
                break;
            case "record":
                this.changes(this.resolve(node.record)).start();
                break;
            case "recorded": {
                const count = this.changes(this.resolve(node.recorded)).history.length;
                if (count !== node.count) {
                    throw new ScenarioFailure(`expected ${node.count} recorded changes, found ${count}`);
                }
                break;
            }
            case "undoAll": {
                const changes = this.changes(this.resolve(node.undoAll));
                changes.stop();
                const edits = changes.history;
                for (let index = edits.length - 1; index >= 0; index--) {
                    edits[index].undo();
                }
                this.undone.set(node.undoAll, edits);
                break;
            }
            case "redoAll":
                for (const edit of this.undone.get(node.redoAll) ?? []) {
                    edit.redo();
                }
                this.undone.delete(node.redoAll);
                break;
            case "write": {
                const root = this.resolve(node.write);
                const snapshot = this.module.ModelSnapshot;
                this.documents.set(node.as, node.plain === true
                    ? snapshot.writePlain(root, root.constructor, node.references === "omit" ? "omit" : "reject")
                    : snapshot.write(root));
                break;
            }
            case "read": {
                const type = this.module[node.type];
                if (typeof type !== "function") {
                    throw new ScenarioFailure(`unknown type ${node.type}`);
                }
                const snapshot = this.module.ModelSnapshot;
                const text = this.document(node.read);
                this.labels.set(node.as, node.plain === true ? snapshot.readPlain(text, type) : snapshot.read(text, type));
                break;
            }
            case "document": {
                const actual = parseJson(this.document(node.document));
                if (!sameJson(actual, node.is)) {
                    throw new ScenarioFailure(`${node.document}: expected ${stringifyJson(node.is)}, written ${stringifyJson(actual)}`);
                }
                break;
            }
            default:
                throw new ScenarioFailure(`unsupported operation ${operation}`);
        }
    }

    /** A written document by name, or a literal {"json": ...} document from the scenario. */
    document(source) {
        if (typeof source === "string") {
            if (!this.documents.has(source)) {
                throw new ScenarioFailure(`unknown document '${source}'`);
            }
            return this.documents.get(source);
        }
        return stringifyJson(source.json);
    }

    // ------------------------------------------------------------------------------------------------ events

    changes(model) {
        if (!isModel(model) || !(model.vmfChanges instanceof vmf.ModelChanges)) {
            throw new ScenarioFailure(`not an observable model object: ${this.render(model)}`);
        }
        return model.vmfChanges;
    }

    /** The listeners of a model object or read-only view. */
    observable(path) {
        const value = this.resolve(path);
        const changes = typeof value === "object" && value !== null ? value.vmfChanges : undefined;
        if (!(changes instanceof vmf.ModelChanges) && !(changes instanceof vmf.ReadOnlyModelChanges)) {
            throw new ScenarioFailure(`not an observable model object: ${this.render(value)}`);
        }
        return changes;
    }

    listen(path, name, scope, node) {
        const received = this.events.get(name) ?? [];
        this.events.set(name, received);
        let log;
        if (has(node, "log")) {
            log = this.calls.get(node.log) ?? [];
            this.calls.set(node.log, log);
        }
        const remove = this.observable(path).addListener(change => {
            const event = {
                step: this.step,
                source: change.source,
                property: change.property,
                kind: change.kind,
                oldValue: change.oldValue ?? null,
                newValue: change.newValue ?? null,
                elements: [...(change.values ?? [])],
                indices: [...(change.indices ?? [])],
            };
            received.push(event);
            log?.push({ listener: name, event });
            if (has(node, "react") && this.matches(node.react.when, event)) {
                // the listener edits the model while the change is delivered
                const edit = node.react.do;
                this.execute(operationOf(edit), edit);
            }
        }, scope === "local" ? "local" : "recursive");
        this.subscriptions.set(name, remove);
    }

    checkCalls(name, expected) {
        const log = this.calls.get(name) ?? [];
        const received = [...log];
        log.length = 0;
        const rendered = `[${received.map(({ listener, event }) => `${listener} ${event.property} `
            + `${this.render(event.oldValue)}->${this.render(event.newValue)}`).join(", ")}]`;
        const same = received.length === expected.length && expected.every((wanted, index) =>
            wanted.listener === received[index].listener && this.matches(wanted, received[index].event));
        if (!same) {
            throw new ScenarioFailure(`${name}: expected ${JSON.stringify(expected)}, received ${rendered}`);
        }
    }

    checkEvents(name, node) {
        const buffer = this.events.get(name) ?? [];
        let received = merged([...buffer]);
        buffer.length = 0;
        if (has(node, "ignore")) {
            received = received.filter(event => !node.ignore.some(pattern => this.matches(pattern, event)));
        }
        if (has(node, "count")) {
            if (received.length !== node.count) {
                throw new ScenarioFailure(`${name}: expected ${node.count} events, received ${this.renderEvents(received)}`);
            }
            return;
        }
        const expected = node.are;
        const failure = () => new ScenarioFailure(`${name}: expected ${JSON.stringify(expected, bigintJson)}, `
            + `received ${this.renderEvents(received)}`);
        if (expected.length !== received.length) {
            throw failure();
        }
        const remaining = [...received];
        for (const wanted of expected) {
            const candidates = node.anyOrder === true ? remaining.length : 1;
            let match = -1;
            for (let index = 0; index < candidates; index++) {
                if (this.matches(wanted, remaining[index])) {
                    match = index;
                    break;
                }
            }
            if (match < 0) {
                throw failure();
            }
            remaining.splice(match, 1);
        }
    }

    matches(wanted, event) {
        if (has(wanted, "on") && this.resolve(wanted.on) !== event.source) return false;
        if (has(wanted, "prop") && wanted.prop !== event.property) return false;
        if (has(wanted, "kind") && wanted.kind !== event.kind) return false;
        if (has(wanted, "old") && !sameScalar(event.oldValue, wanted.old)) return false;
        if (has(wanted, "new") && !sameScalar(event.newValue, wanted.new)) return false;
        if (has(wanted, "oldObj") && this.object(wanted.oldObj) !== event.oldValue) return false;
        if (has(wanted, "newObj") && this.object(wanted.newObj) !== event.newValue) return false;
        if (has(wanted, "values") && !sameScalar(event.elements, wanted.values)) return false;
        if (has(wanted, "objs") && !sameObjects(event.elements, wanted.objs.map(label => this.object(label)), false)) {
            return false;
        }
        if (has(wanted, "index")) {
            return wanted.index.length === event.indices.length
                && wanted.index.every((index, position) => index === event.indices[position]);
        }
        return true;
    }

    renderEvents(events) {
        return `[${events.map(event => `{${this.render(event.source)}.${event.property} ${event.kind} `
            + (event.kind === "set" ? `${this.render(event.oldValue)}->${this.render(event.newValue)}`
                : `${this.render(event.elements)}@[${event.indices.join(",")}]`) + "}").join(", ")}]`;
    }

    // ------------------------------------------------------------------------------------------------ objects

    /**
     * Creates an instance of a registered concrete type, then sets the initial values (TypeScript has no builders).
     * An immutable type receives its values in the constructor.
     */
    create(type, initial) {
        const constructor = this.module[type];
        if (typeof constructor !== "function") {
            throw new ScenarioFailure(`unknown type ${type}`);
        }
        const registered = this.registry.types.find(candidate => candidate.runtimeType === constructor);
        if (registered === undefined) {
            throw new TypeError(`${type} is interface-only and cannot be created.`);
        }
        if (registered.immutable === true) {
            const values = {};
            for (const [property, value] of Object.entries(initial ?? {})) {
                const meta = registered.properties.find(candidate => (candidate.name ?? candidate.wireName) === property);
                const scalarType = meta !== undefined && (meta.kind === "scalar" || meta.kind === "scalarList")
                    ? meta.scalarType : undefined;
                values[property] = Array.isArray(value) ? value.map(item => this.convert(item, scalarType))
                    : this.convert(value, scalarType);
            }
            return new constructor(values);
        }
        const instance = new constructor();
        for (const [property, value] of Object.entries(initial ?? {})) {
            const current = this.get(instance, property);
            const scalarType = this.scalarTypeOf(instance, property);
            if (isCollection(current)) {
                for (const item of value) {
                    current.add(this.convert(item, scalarType));
                }
            } else {
                instance[property] = this.convert(value, scalarType);
            }
        }
        return instance;
    }

    content(value) {
        if (!isModel(value) || typeof value.contentEquals !== "function") {
            throw new ScenarioFailure(`not a model object: ${this.render(value)}`);
        }
        return value;
    }

    get(owner, property) {
        if (typeof owner !== "object" || owner === null) {
            throw new ScenarioFailure(`not a model object: ${this.render(owner)}`);
        }
        if (!(property in owner)) {
            throw new ScenarioFailure(`${owner.constructor.name} has no property '${property}'`);
        }
        return owner[property];
    }

    scalarTypeOf(owner, property) {
        if (!isModel(owner)) {
            return undefined;
        }
        const type = this.registry.types.find(candidate => candidate.runtimeType === owner.constructor);
        const meta = type?.properties.find(candidate => (candidate.name ?? candidate.wireName) === property);
        return meta !== undefined && (meta.kind === "scalar" || meta.kind === "scalarList") ? meta.scalarType : undefined;
    }

    resolve(path) {
        const segments = parse(path);
        const [label, labelIndex] = segments[0];
        if (!this.labels.has(label)) {
            throw new ScenarioFailure(`unknown label '${label}'`);
        }
        let current = this.index(this.labels.get(label), labelIndex, path);
        for (const [name, index] of segments.slice(1)) {
            if (current === null || current === undefined) {
                throw new ScenarioFailure(`null before '${name}' in ${path}`);
            }
            current = this.index(this.get(current, name), index, path);
        }
        return current;
    }

    index(value, index, path) {
        if (index === undefined) {
            return value;
        }
        if (!isCollection(value)) {
            throw new ScenarioFailure(`cannot index ${path}`);
        }
        return [...value][index];
    }

    /** Resolves everything but the last segment and returns [owner, property, index]. */
    owner(path) {
        const dot = path.lastIndexOf(".");
        if (dot < 0) {
            throw new ScenarioFailure(`expected owner.property in ${path}`);
        }
        const [name, index] = parse(path.substring(dot + 1))[0];
        return [this.resolve(path.substring(0, dot)), name, index];
    }

    list(path) {
        const value = this.resolve(path);
        if (!isCollection(value)) {
            throw new ScenarioFailure(`${path} is not a list`);
        }
        return value;
    }

    object(label) {
        return label === null || label === undefined ? null : this.resolve(label);
    }

    convert(value, scalarType) {
        if (value === null || value === undefined) {
            return null;
        }
        if (typeof value === "object" && !Array.isArray(value) && has(value, "obj")) {
            return this.object(value.obj);
        }
        if (typeof value === "number" || typeof value === "bigint") {
            return scalarType === "int64" ? BigInt(value) : Number(value);
        }
        return value;
    }

    single(node, scalarType) {
        if (has(node, "obj")) {
            return this.object(node.obj);
        }
        if (has(node, "value")) {
            return this.convert(node.value, scalarType);
        }
        throw new ScenarioFailure("missing 'obj' or 'value'");
    }

    many(node, scalarType) {
        return has(node, "objs") ? node.objs.map(label => this.object(label))
            : node.values.map(value => this.convert(value, scalarType));
    }

    // ------------------------------------------------------------------------------------------------ edits

    set(path, node) {
        const [owner, name, index] = this.owner(path);
        const current = this.get(owner, name);
        const value = this.single(node, this.scalarTypeOf(owner, name));
        if (index !== undefined) {
            if (isCollection(current)) {
                requireEditable(current, path);
            }
            if (!isCollection(current) || typeof current.set !== "function") {
                throw new ScenarioFailure(`${path} is not an indexed list`);
            }
            current.set(index, value);
            return;
        }
        if (isCollection(current)) {
            throw new ScenarioFailure(`${path} is a list`);
        }
        owner[name] = value;
    }

    add(path, node, index) {
        const [owner, name] = this.owner(path);
        const current = this.get(owner, name);
        if (!isCollection(current)) {
            throw new ScenarioFailure(`${path} is not a list`);
        }
        requireEditable(current, path);
        const scalarType = this.scalarTypeOf(owner, name);
        const values = has(node, "objs") || has(node, "values") ? this.many(node, scalarType) : [this.single(node, scalarType)];
        values.forEach((value, offset) => {
            if (index < 0) {
                current.add(value);
            } else if (typeof current.insert !== "function") {
                throw new ScenarioFailure(`${path} is unordered`);
            } else {
                current.insert(index + offset, value);
            }
        });
    }

    remove(path, node) {
        const list = this.list(path);
        requireEditable(list, path);
        const items = [...list];
        let position;
        if (has(node, "at")) {
            position = node.at;
        } else {
            const identity = has(node, "obj");
            const target = identity ? this.object(node.obj) : undefined;
            position = items.findIndex(item => identity ? item === target : sameScalar(item, node.value));
            if (position < 0) {
                throw new ScenarioFailure(`nothing to remove from ${path}`);
            }
        }
        if (typeof list.removeAt === "function") {
            list.removeAt(position);
        } else {
            list.remove(items[position]);
        }
    }

    // ------------------------------------------------------------------------------------------------ checks

    check(path, node) {
        const actual = this.resolve(path);
        if (has(node, "obj")) {
            const expected = this.object(node.obj);
            if (actual !== expected) {
                throw new ScenarioFailure(`${path}: expected ${this.render(expected)}, found ${this.render(actual)}`);
            }
        }
        if (has(node, "value") && !sameScalar(actual, node.value)) {
            throw new ScenarioFailure(`${path}: expected ${JSON.stringify(node.value, bigintJson)}, found ${this.render(actual)}`);
        }
        if (has(node, "objs") || has(node, "values") || has(node, "size")) {
            if (!isCollection(actual)) {
                throw new ScenarioFailure(`${path} is not a collection: ${this.render(actual)}`);
            }
            const items = [...actual];
            if (has(node, "size") && items.length !== node.size) {
                throw new ScenarioFailure(`${path}: expected size ${node.size}, found ${items.length} ${this.render(items)}`);
            }
            if (has(node, "objs")) {
                const expected = node.objs.map(label => this.object(label));
                if (!sameObjects(items, expected, node.unordered === true)) {
                    throw new ScenarioFailure(`${path}: expected ${this.render(expected)}, found ${this.render(items)}`);
                }
            }
            if (has(node, "values") && !sameScalar(items, node.values)) {
                throw new ScenarioFailure(`${path}: expected ${JSON.stringify(node.values, bigintJson)}, found ${this.render(items)}`);
            }
        }
    }

    pairs(paths, check) {
        const left = this.resolve(paths[0]);
        for (const path of paths.slice(1)) {
            try {
                check(left, this.resolve(path));
            } catch (error) {
                if (error instanceof ScenarioFailure) {
                    throw new ScenarioFailure(`${paths[0]} vs ${path}: ${error.message}`);
                }
                throw error;
            }
        }
    }

    /** The reflection of a model object, through its generated vmfType property. */
    reflect(path) {
        const value = this.resolve(path);
        const type = typeof value === "object" && value !== null ? value.vmfType : undefined;
        if (type === undefined || !Array.isArray(type.properties)) {
            throw new ScenarioFailure(`no reflection for ${this.render(value)}`);
        }
        return type;
    }

    checkAnnotations(path, expected) {
        let annotations;
        if (path.includes(".")) {
            const dot = path.lastIndexOf(".");
            const name = path.substring(dot + 1);
            const property = this.reflect(path.substring(0, dot)).property(name);
            if (property === undefined) {
                throw new ScenarioFailure(`${path}: no such property`);
            }
            annotations = property.annotations;
        } else {
            annotations = this.reflect(path).annotations;
        }
        const actual = annotations.filter(annotation => !annotation.key.startsWith("vmf:"))
            .map(annotation => `${annotation.key}=${annotation.value}`);
        const wanted = expected.map(([key, value]) => `${key}=${value}`);
        if (actual.length !== wanted.length || actual.some((entry, index) => entry !== wanted[index])) {
            throw new ScenarioFailure(`${path}: expected annotations ${JSON.stringify(wanted)}, found ${JSON.stringify(actual)}`);
        }
    }

    // ------------------------------------------------------------------------------------------------ rendering

    render(value) {
        if (value === null || value === undefined) {
            return "null";
        }
        if (Array.isArray(value) || isCollection(value)) {
            return `[${[...value].map(item => this.render(item)).join(", ")}]`;
        }
        if (isModel(value)) {
            for (const [label, labelled] of this.labels) {
                if (labelled === value) {
                    return label;
                }
            }
            return `<${value.constructor.name}>`;
        }
        return typeof value === "string" ? `"${value}"` : String(value);
    }

    /** A textual dump of every object reachable from a label, used to prove rejected edits changed nothing. */
    fingerprint() {
        const ids = new Map();
        const pending = [];
        const reference = value => {
            if (!isModel(value)) {
                return `${typeof value}:${String(value)}`;
            }
            let id = ids.get(value);
            if (id === undefined) {
                id = ids.size;
                ids.set(value, id);
                pending.push(value);
            }
            return `#${id}`;
        };
        let text = "";
        for (const [label, value] of this.labels) {
            text += `${label}=${reference(value)} `;
        }
        for (let index = 0; index < pending.length; index++) {
            const current = pending[index];
            const link = current.__vmfContainerLink;
            text += `#${ids.get(current)}^${reference(link?.owner ?? null)}.${link?.property ?? ""}{`;
            const type = this.registry.types.find(candidate => candidate.runtimeType === current.constructor);
            for (const property of type?.properties ?? []) {
                const value = property.get(current);
                text += `${property.name ?? property.wireName}=`;
                text += isCollection(value) ? `[${[...value].map(reference).join(",")}]` : reference(value);
                text += ";";
            }
            text += "} ";
        }
        return text;
    }
}

await main();
