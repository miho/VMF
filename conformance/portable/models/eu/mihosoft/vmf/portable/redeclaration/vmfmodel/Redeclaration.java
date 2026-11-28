package eu.mihosoft.vmf.portable.redeclaration.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;

// Subtypes that declare inherited properties again, from test-suite vmftest/propertyinheritance (2022),
// vflow (2017) and VMF-Text grammar models: the same property with a new default, getter-only references that
// subtypes narrow or implement as relationship ends, and a type with two supertypes that are not interface-only.

@InterfaceOnly
interface Ranked {
    @DefaultValue("\"unnamed\"")
    String getName();

    int getRank();
}

interface Item extends Ranked {
    @DefaultValue("\"item\"")
    String getName();

    int getRank();
}

interface SpecialItem extends Item {
    @DefaultValue("\"special\"")
    String getName();
}

@Immutable
interface Version {
    @DefaultValue("1")
    int getMajor();

    String getTag();
}

@Immutable
interface Release extends Version {
    @DefaultValue("2")
    int getMajor();
}

// Immutable types with two immutable supertypes (the first supertype's default wins), and an immutable subtype
// that narrows the reference it inherits.
@Immutable
interface LeftRank {
    @DefaultValue("5")
    int getRank();
}

@Immutable
interface RightRank {
    @DefaultValue("11")
    int getRank();

    String getNote();
}

@Immutable
interface LiteralRank extends LeftRank, RightRank {
}

@Immutable
interface ValueTag {
    String getTitle();
}

@Immutable
interface ColorTag extends ValueTag {
    String getColor();
}

@Immutable
interface FrozenChoice {
    ValueTag getCurrent();
}

@Immutable
interface FrozenColorChoice extends FrozenChoice {
    ColorTag getCurrent();
}

// A port's node and wires are relationship ends that each kind of port declares itself.
@InterfaceOnly
interface Port {
    String getName();

    @GetterOnly
    Node getNode();

    @GetterOnly
    Wire[] getWires();
}

interface InPort extends Port {
    @Container(opposite = "inputs")
    Node getNode();

    @Contains(opposite = "target")
    Wire[] getWires();
}

interface OutPort extends Port {
    @Container(opposite = "outputs")
    Node getNode();

    @Contains(opposite = "source")
    Wire[] getWires();
}

interface Node {
    @Contains(opposite = "node")
    InPort[] getInputs();

    @Contains(opposite = "node")
    OutPort[] getOutputs();
}

interface Wire {
    String getName();

    @Container(opposite = "wires")
    InPort getTarget();

    @Container(opposite = "wires")
    OutPort getSource();
}

// Getter-only traits that narrow the shape they return.
interface Shape {
    String getLabel();
}

interface Circle extends Shape {
    double getRadius();
}

@InterfaceOnly
interface WithShape {
    @GetterOnly
    Shape getShape();
}

@InterfaceOnly
interface WithCircle extends WithShape {
    @GetterOnly
    Circle getShape();
}

interface Badge extends WithCircle {
    Circle getShape();
}

// Two supertypes that are not interface-only.
interface Point {
    int getX();
}

interface Tagged {
    String getTag();
}

interface TaggedPoint extends Point, Tagged {
}

// A getter-only trait as the type of contained children, from C# conformance traits (Folder, NamedItem): every
// implementation is mutable, so each can be contained.
@InterfaceOnly
interface Labeled {
    @GetterOnly
    String getLabel();

    @GetterOnly
    @Container(opposite = "items")
    Shelf getShelf();
}

interface Book extends Labeled {
    String getIsbn();
}

interface Note extends Labeled {
    String getText();
}

interface Shelf {
    @Contains(opposite = "shelf")
    Labeled[] getItems();

    @Contains
    Labeled getFirst();
}

// Two concrete supertypes that both declare the same containment list, from C# conformance exactredeclarations.
interface Asset {
    String getName();
}

interface FirstRecord {
    @Contains
    Asset[] getAssets();
}

interface SecondRecord {
    @Contains
    Asset[] getAssets();
}

interface CombinedRecord extends FirstRecord, SecondRecord {
}

// A plain reference list of the first supertype that becomes the keyed containment the second declares, from
// test-suite vmftest/keyed (Warehouse).
interface Article {
    String getCode();
}

@InterfaceOnly
interface ArticleRefs {
    Article[] getArticles();
}

@InterfaceOnly
interface ArticleStock {
    @Contains
    @Keyed(by = "code")
    Article[] getArticles();
}

interface Store extends ArticleRefs, ArticleStock {
}
