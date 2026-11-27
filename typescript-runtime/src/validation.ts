/** A failed model constraint, identified by its code and model path. */
export interface ValidationIssue {
    readonly code: string;
    readonly path: string;
    readonly message: string;
}
