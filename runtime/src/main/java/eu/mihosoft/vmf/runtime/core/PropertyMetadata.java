/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core;

import eu.mihosoft.vmf.runtime.core.internal.AnnotationImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Immutable description of a reflected property's declared type and annotations.
 */
public final class PropertyMetadata {

    private final String name;
    private final TypeDescriptor descriptor;
    private final boolean mapType;
    private final boolean setType;
    private final boolean referenceSetType;
    private final Multiplicity multiplicity;
    private final String typeName;
    private final Optional<String> elementTypeName;
    private final boolean modelType;
    private final boolean listType;
    private final boolean interfaceOnly;
    private final List<Annotation> annotations;

    PropertyMetadata(Property property) {
        Type type = property.getType();
        this.name = property.getName();
        this.descriptor = type.getDescriptor();
        this.mapType = type.isMapType();
        this.setType = type.isSetType();
        this.referenceSetType = type.isReferenceSetType();
        this.multiplicity = property.getMultiplicity();
        this.typeName = type.getName();
        this.elementTypeName = type.getElementTypeName();
        this.modelType = type.isModelType();
        this.listType = type.isListType();
        this.interfaceOnly = type.isInterfaceOnly();

        List<Annotation> copiedAnnotations = new ArrayList<>();
        for (Annotation annotation : property.annotations()) {
            copiedAnnotations.add(new AnnotationImpl(annotation.getKey(), annotation.getValue()));
        }
        this.annotations = List.copyOf(copiedAnnotations);
    }

    /** Returns the structured property type, including its concrete arguments. */
    public TypeDescriptor getDescriptor() { return descriptor; }

    public boolean isSetType() { return setType; }

    /** Whether the property holds unique model references. See its multiplicity for ordering. */
    public boolean isReferenceSetType() { return referenceSetType; }

    public Multiplicity getMultiplicity() { return multiplicity; }

    /** Indicates whether the property is a managed map. */
    public boolean isMapType() { return mapType; }

    /** Returns the entry property used for keyed lookup on a containment list. */
    public Optional<String> getKeyPropertyName() {
        return annotations.stream().filter(a -> a.getKey().equals("vmf:collection:key")).map(Annotation::getValue).findFirst();
    }

    /** Returns the property name. */
    public String getName() {
        return name;
    }

    /** Returns the declared type name, including the element type for lists. */
    public String getTypeName() {
        return typeName;
    }

    /** Returns the collection element type name when present. */
    public Optional<String> getElementTypeName() {
        return elementTypeName;
    }

    /** Indicates whether the property's type or its list element type is a model type. */
    public boolean isModelType() {
        return modelType;
    }

    /** Indicates whether the property is a list. */
    public boolean isListType() {
        return listType;
    }

    /** Indicates whether the declared type is an interface-only model type. */
    public boolean isInterfaceOnly() {
        return interfaceOnly;
    }

    /** Returns an immutable list of annotations in reflection order. */
    public List<Annotation> annotations() {
        return annotations;
    }
}
