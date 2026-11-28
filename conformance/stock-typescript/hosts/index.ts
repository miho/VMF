// Host types that the test-suite and vmf-jackson models import from "@vmftest/hosts" (external types with a
// "vmf:typescript:external-module" annotation). The stock gate only type-checks them.
export class JavaList {
    readonly items: unknown[] = [];
}

export interface ModelAction {
    run(): void;
}

export class Image {
    width = 0;
}

export class BufferedImage extends Image {
    pixels: number[] = [];
}

export class DocumentRevision {
    constructor(readonly number: number) {
    }
}

export class StringBuilder {
    text = "";
}

export class JavaException {
    constructor(readonly message: string | null) {
    }
}

export class JavaMap {
    readonly entries = new Map<string, unknown>();
}
