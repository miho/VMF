/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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

