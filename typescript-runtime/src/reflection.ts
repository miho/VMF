/** A key/value pair of an `@Annotation`. Keys starting with "vmf:" are reserved for VMF. */
export interface ModelAnnotation {
    readonly key: string;
    readonly value: string;
}

/** A property of a model type and its annotations in declaration order. */
export interface ModelProperty {
    readonly name: string;
    readonly annotations: readonly ModelAnnotation[];
    /** The values of the annotations with this key, in declaration order. */
    annotationValues(key: string): readonly string[];
}

/**
 * The reflection of a model type: its properties in VMF property order (supertypes first, then by
 * `@PropertyOrder` or alphabetically) and its annotations in declaration order.
 */
export interface ModelType {
    /** The qualified name of the model type. */
    readonly name: string;
    /** True for `@Immutable` types, whose objects cannot change after construction. */
    readonly immutable: boolean;
    readonly properties: readonly ModelProperty[];
    readonly annotations: readonly ModelAnnotation[];
    property(name: string): ModelProperty | undefined;
    annotationValues(key: string): readonly string[];
}

/** Annotations as generated: [key, value] pairs. */
export type AnnotationPairs = readonly (readonly [string, string])[];

// eslint-disable-next-line @typescript-eslint/no-unsafe-function-type
type AnyClass = Function;

const declared = new WeakMap<AnyClass, ModelType>();
const lookups = new WeakMap<ModelType, readonly string[]>();
let unwrap: (value: object) => object = value => value;

/**
 * @internal Generated code declares every model class with its reflection data. `keyLookups` names the methods that
 * find entries of keyed containments by key, which read-only views offer too.
 */
export function declareModelType(type: AnyClass, name: string, immutable: boolean, annotations: AnnotationPairs,
                                 properties: readonly (readonly [string, AnnotationPairs])[],
                                 keyLookups: readonly string[] = []): void {
    const propertyList = Object.freeze(properties.map(([propertyName, pairs]) => {
        const propertyAnnotations = annotationList(pairs);
        return Object.freeze({
            name: propertyName,
            annotations: propertyAnnotations,
            annotationValues: (key: string) => valuesOf(propertyAnnotations, key),
        });
    }));
    const typeAnnotations = annotationList(annotations);
    const modelType: ModelType = Object.freeze({
        name,
        immutable,
        properties: propertyList,
        annotations: typeAnnotations,
        property: (propertyName: string) => propertyList.find(property => property.name === propertyName),
        annotationValues: (key: string) => valuesOf(typeAnnotations, key),
    });
    declared.set(type, modelType);
    lookups.set(modelType, Object.freeze([...keyLookups]));
}

/** @internal The key lookup methods of a model type, including those of its supertypes. */
export function __vmfLookupsOf(type: ModelType): readonly string[] {
    return lookups.get(type) ?? [];
}

/** @internal Lets read-only views report the type of the object they show. */
export function __vmfUseViews(lookup: (value: object) => object): void {
    unwrap = lookup;
}

/** The reflection of a model object, an immutable value or a read-only view. */
export function modelTypeOf(value: object): ModelType {
    const type = findModelType(value);
    if (type === undefined) {
        throw new TypeError("Expected a VMF model object.");
    }
    return type;
}

/** @internal The reflection of a model object, or undefined for other values. */
export function findModelType(value: unknown): ModelType | undefined {
    if (typeof value !== "object" || value === null) {
        return undefined;
    }
    const model = unwrap(value);
    return declared.get(Object.getPrototypeOf(model)?.constructor);
}

function annotationList(pairs: AnnotationPairs): readonly ModelAnnotation[] {
    return Object.freeze(pairs.map(([key, value]) => Object.freeze({ key, value })));
}

function valuesOf(annotations: readonly ModelAnnotation[], key: string): readonly string[] {
    return Object.freeze(annotations.filter(annotation => annotation.key === key).map(annotation => annotation.value));
}
