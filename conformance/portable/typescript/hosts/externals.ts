// Host code of the portable model "externals" (contract module "externals"): the TypeScript side of the external
// type Distance and the bindings that runner.mjs installs.
import type { ModelBindings } from "../build/generated/eu/mihosoft/vmf/portable/externals/model.g.js";

/** The host value of the external type Distance; documents write it as {"meters": number}. */
export class Distance {
    constructor(readonly meters: number) {
    }
}

export const bindings: ModelBindings = {
    externals: {
        Distance: {
            write: distance => ({ meters: distance.meters }),
            read: json => new Distance(Number((json as { readonly meters: number }).meters)),
            // Java's Distance.equals compares the doubles with Double.compare.
            equals: (left, right) => Object.is(left.meters, right.meters),
            hashCode: distance => [...String(distance.meters)]
                .reduce((hash, character) => (Math.imul(hash, 31) + character.charCodeAt(0)) | 0, 0),
        },
    },
};
