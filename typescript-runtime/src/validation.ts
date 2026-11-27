/** How serious a validation issue is. Declared multiplicities and value constraints report errors. */
export type ValidationSeverity = "error" | "warning" | "info";

/**
 * A failed model constraint. `modelCode` and `modelPath` are the same in every VMF target: the code
 * `vmf.multiplicity`, `vmf.range`, `vmf.length` or `vmf.pattern`, and a JSON pointer of model property names and
 * list indices from the validated object, such as `/segments/0/code`. `code` is the short code (`range`) and
 * `path` the TypeScript member path (`segments[0].code`). Messages are written for people and differ between
 * targets.
 */
export interface ValidationIssue {
    readonly code: string;
    readonly path: string;
    readonly message: string;
    readonly modelCode: string;
    readonly modelPath: string;
    readonly severity: ValidationSeverity;
}

/** @internal A model object whose generated code checks its declared multiplicities and value constraints. */
export interface ValidatedModel {
    __vmfValidateInto(issues: ValidationIssue[], path: string, modelPath: string, pending: ValidationTask[]): void;
}

/** @internal A contained object still to validate, with its member path prefix and its model path. */
export type ValidationTask = readonly [model: ValidatedModel, path: string, modelPath: string];

/**
 * Validates a model object and the objects it contains, depth first: an object before the objects it contains,
 * those in property order and then in list order. Each object checks its properties and pushes its children, the
 * last one first, so that deep trees do not deepen the call stack. References are not followed.
 */
export function validateTree(root: ValidatedModel): readonly ValidationIssue[] {
    const issues: ValidationIssue[] = [];
    const pending: ValidationTask[] = [[root, "", ""]];
    for (let task = pending.pop(); task !== undefined; task = pending.pop()) {
        task[0].__vmfValidateInto(issues, task[1], task[2], pending);
    }
    return Object.freeze(issues);
}

/** Reports a property whose number of values is outside its bounds; an `upper` of -1 is no upper bound. */
export function checkMultiplicity(issues: ValidationIssue[], path: string, modelPath: string, name: string,
                                  count: number, lower: number, upper: number): void {
    if (count >= lower && (upper < 0 || count <= upper)) {
        return;
    }
    const expected = lower > 0 && upper >= 0 ? `between ${lower} and ${upper}`
        : lower > 0 ? `at least ${lower}` : `at most ${upper}`;
    report(issues, "multiplicity", path, modelPath, name, `Expected ${expected}, found ${count}.`);
}

/**
 * Reports a byte, short, int or long value outside a range of decimal bounds (`null`: no bound). The comparison is
 * exact, also for `bigint` values beyond 2^53 and for bounds that are not integers.
 */
export function checkRange(issues: ValidationIssue[], path: string, modelPath: string, name: string,
                           value: number | bigint | null, min: string | null, minInclusive: boolean,
                           max: string | null, maxInclusive: boolean): void {
    if (value === null) {
        return;
    }
    if (typeof value === "bigint" || (typeof value === "number" && Number.isFinite(value))) {
        const number = parseDecimal(String(value));
        if ((min === null || compareDecimals(number, bound(min)) >= (minInclusive ? 0 : 1))
            && (max === null || compareDecimals(number, bound(max)) <= (maxInclusive ? 0 : -1))) {
            return;
        }
    }
    report(issues, "range", path, modelPath, name, rangeMessage(min, minInclusive, max, maxInclusive));
}

/**
 * Reports a float or double value outside a range, NaN or an infinity. A value is compared by its shortest decimal
 * representation, as Java compares it: the generator turns the decimal bounds into the lowest and highest value
 * whose shortest decimal lies within them. A float property is compared at float precision.
 */
export function checkFloatRange(issues: ValidationIssue[], path: string, modelPath: string, name: string,
                                value: number | null, float32: boolean, lowest: number, highest: number,
                                min: string | null, minInclusive: boolean, max: string | null,
                                maxInclusive: boolean): void {
    if (value === null) {
        return;
    }
    const number = float32 ? Math.fround(value) : value;
    if (typeof number === "number" && Number.isFinite(number) && number >= lowest && number <= highest) {
        return;
    }
    report(issues, "range", path, modelPath, name, rangeMessage(min, minInclusive, max, maxInclusive));
}

/** Reports a string whose number of Unicode code points is outside `min..max`; a `max` of -1 is no maximum. */
export function checkLength(issues: ValidationIssue[], path: string, modelPath: string, name: string,
                            value: string | null, min: number, max: number): void {
    if (value === null) {
        return;
    }
    const count = codePoints(value);
    if (count >= min && (max < 0 || count <= max)) {
        return;
    }
    report(issues, "length", path, modelPath, name, `${min}..${max < 0 ? "*" : max} Unicode code points`);
}

/**
 * Reports a string in which a portable pattern finds no match. `pattern` is the ECMAScript form of the model's
 * pattern, whose `$` only matches at the end; like Java's `find()`, the search is not anchored. `source` is the
 * pattern as the model declares it.
 */
export function checkPattern(issues: ValidationIssue[], path: string, modelPath: string, name: string,
                             value: string | null, pattern: string, source: string): void {
    if (value !== null && !compiled(pattern, "").test(value)) {
        report(issues, "pattern", path, modelPath, name, `a match for /${source}/`);
    }
}

/**
 * Reports a string in which the TypeScript binding (`vmf:pattern:typescript`) of a Java-only pattern finds no
 * match. The binding is an ECMAScript regular expression, searched with the `u` flag.
 */
export function checkBoundPattern(issues: ValidationIssue[], path: string, modelPath: string, name: string,
                                  value: string | null, pattern: string, javaPattern: string): void {
    if (value !== null && !compiled(pattern, "u").test(value)) {
        report(issues, "pattern", path, modelPath, name, `a match for /${javaPattern}/`);
    }
}

function report(issues: ValidationIssue[], code: string, path: string, modelPath: string, name: string,
                message: string): void {
    issues.push(Object.freeze({
        code,
        path: path + name,
        message,
        modelCode: "vmf." + code,
        modelPath: modelPath + "/" + name.replaceAll("~", "~0").replaceAll("/", "~1"),
        severity: "error",
    }));
}

function rangeMessage(min: string | null, minInclusive: boolean, max: string | null, maxInclusive: boolean): string {
    const lower = min === null ? "" : (minInclusive ? ">= " : "> ") + min;
    const upper = max === null ? "" : (maxInclusive ? "<= " : "< ") + max;
    return "a finite number " + lower + (min !== null && max !== null ? " and " : "") + upper;
}

/** Counts code points: a surrogate pair is one, as is an unpaired surrogate. */
function codePoints(text: string): number {
    let count = text.length;
    for (let index = 0; index + 1 < text.length; index++) {
        const unit = text.charCodeAt(index);
        if (unit >= 0xd800 && unit <= 0xdbff) {
            const next = text.charCodeAt(index + 1);
            if (next >= 0xdc00 && next <= 0xdfff) {
                count--;
                index++;
            }
        }
    }
    return count;
}

const expressions = new Map<string, RegExp>();

function compiled(pattern: string, flags: string): RegExp {
    const key = flags + "/" + pattern;
    let expression = expressions.get(key);
    if (expression === undefined) {
        expression = new RegExp(pattern, flags);
        expressions.set(key, expression);
    }
    return expression;
}

/**
 * A decimal number without rounding: its sign, the position of the decimal point relative to the first significant
 * digit (0.d1d2... times 10^point) and the significant digits without trailing zeros.
 */
interface Decimal {
    readonly sign: number;
    readonly point: number;
    readonly digits: string;
}

const bounds = new Map<string, Decimal>();

function bound(text: string): Decimal {
    let decimal = bounds.get(text);
    if (decimal === undefined) {
        decimal = parseDecimal(text);
        bounds.set(text, decimal);
    }
    return decimal;
}

function parseDecimal(text: string): Decimal {
    const parts = /^([+-]?)([0-9]*)(?:\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?$/.exec(text);
    const integer = parts?.[2] ?? "";
    const digits = integer + (parts?.[3] ?? "");
    if (parts === null || digits.length === 0) {
        throw new RangeError(`Invalid decimal value '${text}'.`);
    }
    const first = digits.search(/[1-9]/);
    if (first < 0) {
        return { sign: 0, point: 0, digits: "" };
    }
    return {
        sign: parts[1] === "-" ? -1 : 1,
        point: Number(parts[4] ?? 0) + integer.length - first,
        digits: digits.substring(first).replace(/0+$/, ""),
    };
}

function compareDecimals(left: Decimal, right: Decimal): number {
    if (left.sign !== right.sign || left.sign === 0) {
        return Math.sign(left.sign - right.sign);
    }
    const magnitude = left.point !== right.point ? (left.point < right.point ? -1 : 1)
        : left.digits < right.digits ? -1 : left.digits > right.digits ? 1 : 0;
    return left.sign * magnitude;
}
