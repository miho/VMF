package eu.mihosoft.vmftest.complex.vmf_text.generated.json.vmfmodel;

// TODO 19.08.2018 get rid of this import to prevent name clashes
import eu.mihosoft.vmf.core.*;

interface JSONModel {
  Json getRoot();
}

@eu.mihosoft.vmf.core.Immutable
interface CodeRange {
  CodeLocation getStart();
  CodeLocation getStop();
  int getLength();
}

@eu.mihosoft.vmf.core.Immutable
interface CodeLocation {
  int getIndex();
  int getLine();
  int getCharPosInLine();
}

@eu.mihosoft.vmf.core.InterfaceOnly
//@eu.mihosoft.vmf.core.DelegateTo(className="eu.mihosoft.vmftext.tests.json.vmfdelegation.CodeElementDelegate")
interface CodeElement {
  @eu.mihosoft.vmf.core.IgnoreToString
  @eu.mihosoft.vmf.core.IgnoreEquals
  CodeRange getCodeRange();

  @eu.mihosoft.vmf.core.IgnoreEquals
  CodeElement getParent();

//   @eu.mihosoft.vmf.core.DelegateTo(className="eu.mihosoft.vmftext.tests.json.vmfdelegation.CodeElementDelegate")
//   CodeElement root();

//   @eu.mihosoft.vmf.core.DelegateTo(className="eu.mihosoft.vmftext.tests.json.vmfdelegation.CodeElementDelegate")
//   eu.mihosoft.vcollections.VList<CodeElement> pathToRoot();

  @eu.mihosoft.vmf.core.IgnoreEquals
  Object getPayload();
}

interface Json extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Val getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface Obj extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Pair[] getPairs();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface Pair extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getKey();

  @eu.mihosoft.vmf.core.PropertyOrder(index = 1)
  Val getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface Array extends CodeElement {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Val[] getValues();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

@eu.mihosoft.vmf.core.InterfaceOnly
interface Val extends CodeElement {

  // Properties derived from grammar rule

  // Custom properties (ordered always after regular properties directly derived from the grammar)
  @GetterOnly
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Object getValue();


  // Delegation methods

}

interface StringValue extends Val {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.String getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface NumberValue extends Val {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.Double getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ObjectValue extends Val {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Obj getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface ArrayValue extends Val {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Array getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface BooleanValue extends Val {

  // Properties derived from grammar rule
  @eu.mihosoft.vmf.core.DefaultValue("null")
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  java.lang.Boolean getValue();


  // Custom properties (ordered always after regular properties directly derived from the grammar)

  // Delegation methods

}

interface NullValue extends Val {

  // Properties derived from grammar rule

  // Custom properties (ordered always after regular properties directly derived from the grammar)
  @eu.mihosoft.vmf.core.PropertyOrder(index = 0)
  Object getValue();


  // Delegation methods

}


// Custom model classes

