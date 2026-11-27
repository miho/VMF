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
package eu.mihosoft.vmf.core.codegen.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Model;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Required;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.PortableCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import org.junit.Test;

import static org.junit.Assert.*;

interface RecursiveNode {
    @Contains(opposite = "parent")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    RecursiveNode[] getChildren();

    @Container(opposite = "children")
    RecursiveNode getParent();
}

interface DetailedNode extends RecursiveNode {}

interface BoxWithSlots {
    @Contains(opposite = "box")
    Slot getOptional();

    @Contains
    @Required
    Slot getPrimary();

    @Multiplicity(lower = 1, upper = 4)
    Slot[] getTrail();

    @Multiplicity(lower = 1, upper = 3, kind = Multiplicity.Kind.ORDERED_SET)
    Slot[] getPriority();
}

interface Slot {
    @Container(opposite = "optional")
    BoxWithSlots getBox();
}

@InterfaceOnly
interface InheritedContainer {
    @Container(opposite = "child")
    NarrowOwner getOwner();
}

interface NarrowChild extends InheritedContainer {}

interface IncompatibleChild extends InheritedContainer {}

interface NarrowOwner {
    @Contains(opposite = "owner")
    NarrowChild getChild();
}

interface PlainContainmentList {
    @Contains
    SlotLike[] getValues();
}

interface SlotLike {}

@InterfaceOnly
interface ConnectorName {
    String getName();
}

@InterfaceOnly
interface AbstractPort extends ConnectorName {
    @GetterOnly PortNode getNode();
    @GetterOnly PortLink[] getLinks();
}

interface ConnectedPort extends AbstractPort {
    @Container(opposite = "ports") PortNode getNode();
    @Contains(opposite = "port") PortLink[] getLinks();
}

interface UnconnectedPort extends AbstractPort {}

interface PortNode {
    @Contains(opposite = "node") ConnectedPort[] getPorts();
}

interface PortLink {
    @Container(opposite = "links") ConnectedPort getPort();
}

public class GraphCapabilitiesTest {
    @Test
    public void csharpProjectsAbstractRelationshipGettersWithoutIndependentStorage() {
        ResolvedModel projected = model(ConnectorName.class, AbstractPort.class, ConnectedPort.class,
                PortNode.class, PortLink.class);
        CSharpCapabilities.validate(projected);
        var port = projected.findType("ConnectedPort").orElseThrow();
        var node = property(projected, "ConnectedPort", "node");
        var links = property(projected, "ConnectedPort", "links");
        assertEquals(Property.Kind.CONTAINER, node.getKind());
        assertEquals(Property.Kind.CONTAINMENT, links.getKind());
        assertTrue(CSharpCapabilities.projectsRelationshipGetter(projected, port, node));
        assertTrue(CSharpCapabilities.projectsRelationshipGetter(projected, port, links));
        assertEquals(Property.Kind.REFERENCE, property(projected, "AbstractPort", "node").getKind());
        assertEquals(Property.Kind.REFERENCE, property(projected, "AbstractPort", "links").getKind());

        // TypeScript declares the abstract port's getters abstract; the connected port implements them.
        PortableCapabilities.validate(projected, "TypeScript");

        ResolvedModel incomplete = model(ConnectorName.class, AbstractPort.class, ConnectedPort.class,
                UnconnectedPort.class, PortNode.class, PortLink.class);
        var csharp = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(incomplete));
        assertTrue(csharp.getMessage().contains("ConnectedPort.node redeclares an inherited property"));
        assertTrue(csharp.getMessage().contains("ConnectedPort.links redeclares an inherited property"));
        var typescript = assertThrows(IllegalArgumentException.class,
                () -> PortableCapabilities.validate(incomplete, "TypeScript"));
        assertTrue(typescript.getMessage().contains("ConnectedPort.node redeclares an inherited property"));
    }

    @Test
    public void csharpAcceptsSelfContainmentWithExactInheritedOpposite() {
        ResolvedModel model = model(RecursiveNode.class, DetailedNode.class);
        CSharpCapabilities.validate(model);
        var children = property(model, "DetailedNode", "children");
        assertEquals("RecursiveNode", children.getDeclaringType());
        assertEquals(Property.Kind.CONTAINMENT, children.getKind());
        assertTrue(children.isCollection());
        assertTrue(children.isOrdered());
        assertTrue(children.isUnique());
        assertEquals("parent", children.getOppositeName());
        assertEquals("children", property(model, "DetailedNode", "parent").getOppositeName());

        // TypeScript checks containment cycles at run time and supports opposites declared on base types.
        PortableCapabilities.validate(model, "TypeScript");
    }

    @Test
    public void csharpAcceptsBothSingularCardinalitiesAndBothOrderedReferenceShapes() {
        ResolvedModel model = model(BoxWithSlots.class, Slot.class);
        CSharpCapabilities.validate(model);
        var optional = property(model, "BoxWithSlots", "optional");
        assertEquals(Property.Kind.CONTAINMENT, optional.getKind());
        assertFalse(optional.isCollection());
        assertFalse(optional.isRequired());
        assertEquals(1, optional.getUpperBound());
        assertEquals("box", optional.getOppositeName());

        var primary = property(model, "BoxWithSlots", "primary");
        assertEquals(Property.Kind.CONTAINMENT, primary.getKind());
        assertFalse(primary.isCollection());
        assertTrue(primary.isRequired());
        assertEquals(1, primary.getUpperBound());

        var trail = property(model, "BoxWithSlots", "trail");
        assertEquals(Property.Kind.REFERENCE, trail.getKind());
        assertTrue(trail.isCollection());
        assertTrue(trail.isOrdered());
        assertFalse(trail.isUnique());
        assertEquals(1, trail.getLowerBound());
        assertEquals(4, trail.getUpperBound());

        var priority = property(model, "BoxWithSlots", "priority");
        assertEquals(Property.Kind.REFERENCE, priority.getKind());
        assertTrue(priority.isCollection());
        assertTrue(priority.isOrdered());
        assertTrue(priority.isUnique());
        assertEquals(1, priority.getLowerBound());
        assertEquals(3, priority.getUpperBound());

        var ts = assertThrows(IllegalArgumentException.class, () -> PortableCapabilities.validate(model, "TypeScript"));
        assertFalse(ts.getMessage(), ts.getMessage().contains(".optional "));
        assertFalse(ts.getMessage(), ts.getMessage().contains(".primary "));
        assertFalse(ts.getMessage(), ts.getMessage().contains(".trail "));
        assertTrue(ts.getMessage(), ts.getMessage().contains(
                ".priority needs a single-valued reference, an ordered reference list, or an unbounded unordered"));
    }

    @Test
    public void csharpAcceptsNarrowOppositeWithOnlyCompatibleImplementations() {
        ResolvedModel narrowed = model(NarrowOwner.class, InheritedContainer.class, NarrowChild.class);
        CSharpCapabilities.validate(narrowed);
        assertEquals("InheritedContainer", property(narrowed, "NarrowChild", "owner").getDeclaringType());

        ResolvedModel incompatible = model(NarrowOwner.class, InheritedContainer.class,
                NarrowChild.class, IncompatibleChild.class);
        var inverse = assertThrows(IllegalArgumentException.class, () -> CSharpCapabilities.validate(incompatible));
        assertTrue(inverse.getMessage(), inverse.getMessage().contains(".owner needs a type-compatible reciprocal containment"));
    }

    @Test
    public void csharpAcceptsNullableOrdinaryContainmentLists() {
        ResolvedModel collection = model(PlainContainmentList.class, SlotLike.class);
        CSharpCapabilities.validate(collection);
        var values = property(collection, "PlainContainmentList", "values");
        assertEquals(Property.Kind.CONTAINMENT, values.getKind());
        assertTrue(values.allowsNullElements());
        // A child has one container, so TypeScript treats every ordered containment list as an identity list.
        PortableCapabilities.validate(collection, "TypeScript");
    }

    private static ResolvedModel model(Class<?>... declarations) {
        return ResolvedModel.from(Model.newInstance(declarations), declarations);
    }

    private static Property property(ResolvedModel model, String type, String name) {
        return model.findType(type).orElseThrow().getEffectiveProperties().stream()
                .filter(property -> property.getName().equals(name)).findFirst().orElseThrow();
    }
}
