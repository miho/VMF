/** Keeps numeric tokens lossless until the model supplies their type. */
export class JsonNumber {
    constructor(readonly text: string) {}
}

export type JsonValue = null | boolean | string | JsonNumber | JsonValue[] | Map<string, JsonValue>;

export function parseJson(text: string, reservedFields?: ReadonlySet<string>): JsonValue {
    const token = /[\t\n\r ]*("(?:[^"\\\x00-\x1F]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null|[{}\[\],:])/y;
    let offset = 0;
    let current = nextToken();

    function nextToken(): string | undefined {
        token.lastIndex = offset;
        const match = token.exec(text);
        if (match !== null) {
            offset = token.lastIndex;
            return match[1];
        }
        if (/^[\t\n\r ]*$/.test(text.slice(offset))) {
            return undefined;
        }
        throw new SyntaxError(`Invalid JSON token at offset ${offset}.`);
    }

    function take(expected?: string): string {
        const value = current;
        if (value === undefined || (expected !== undefined && value !== expected)) {
            throw new SyntaxError(`Expected ${expected ?? "a JSON token"} at offset ${offset}.`);
        }
        current = nextToken();
        return value;
    }

    function stringValue(value: string, path: string): string {
        if (!value.startsWith('"')) {
            throw new SyntaxError(`Expected a JSON string at ${path}.`);
        }
        const decoded: string = JSON.parse(value);
        requireValidText(decoded, path);
        return decoded;
    }

    function readValue(path: string, depth: number): JsonValue {
        if (depth > 1000) {
            throw new SyntaxError(`Snapshot JSON nesting is too deep at ${path}.`);
        }
        const value = take();
        switch (value) {
            case "{": {
                const fields = new Map<string, JsonValue>();
                if (current !== "}") {
                    do {
                        if (fields.size !== 0) {
                            take(",");
                        }
                        const key = stringValue(take(), path);
                        const child = childPath(path, key);
                        if (reservedFields?.has(key)) {
                            throw new SyntaxError(`Plain JSON does not accept VMF metadata at ${child}.`);
                        }
                        if (fields.has(key)) {
                            throw new SyntaxError(`Duplicate JSON field '${key}' at ${child}.`);
                        }
                        take(":");
                        fields.set(key, readValue(child, depth + 1));
                    } while (current === ",");
                }
                take("}");
                return fields;
            }
            case "[": {
                const members: JsonValue[] = [];
                if (current !== "]") {
                    do {
                        if (members.length !== 0) {
                            take(",");
                        }
                        members.push(readValue(childPath(path, members.length), depth + 1));
                    } while (current === ",");
                }
                take("]");
                return members;
            }
            case "true": return true;
            case "false": return false;
            case "null": return null;
            default:
                if (value.startsWith('"')) {
                    return stringValue(value, path);
                }
                if (/^-?[0-9]/.test(value)) {
                    return new JsonNumber(value);
                }
                throw new SyntaxError(`Expected a JSON value at ${path}.`);
        }
    }

    const result = readValue("$", 0);
    if (current !== undefined) {
        throw new SyntaxError(`Unexpected token after the VMF JSON document at offset ${offset}.`);
    }
    return result;
}

export function childPath(path: string, field: string | number): string {
    return path + "/" + String(field).replaceAll("~", "~0").replaceAll("/", "~1");
}

export function requireValidText(value: string, path: string): void {
    for (let i = 0; i < value.length; i++) {
        const character = value.charCodeAt(i);
        if (character >= 0xd800 && character <= 0xdbff) {
            const next = value.charCodeAt(++i);
            if (next >= 0xdc00 && next <= 0xdfff) {
                continue;
            }
        } else if (character < 0xdc00 || character > 0xdfff) {
            continue;
        }
        throw new SyntaxError(`Invalid Unicode in string at ${path}.`);
    }
}
