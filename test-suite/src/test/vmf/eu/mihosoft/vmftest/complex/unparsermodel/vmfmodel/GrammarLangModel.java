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
package eu.mihosoft.vmftest.complex.unparsermodel.vmfmodel;

import eu.mihosoft.vmf.core.*;
import static eu.mihosoft.vmf.core.VMFEquals.EqualsType.*;

import java.util.List;
import java.util.Optional;

@VMFModel(
        equality=CONTAINMENT_AND_EXTERNAL
)
// @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.CheckRulesDelegate")
interface GrammarModel {
    @Contains(opposite = "model")
    RuleClass[] getRuleClasses();

    String getGrammarName();

    String getPackageName();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.GetRootClassDelegate")
    // boolean hasRootClass();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.GetRootClassDelegate")
    // RuleClass rootClass();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.RuleClassLookup")
    // Optional<RuleClass> ruleClassByName(String name);

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.RuleClassLookup")
    // Optional<Property> propertyByName(String ruleClassName, String propName);

    @Contains(opposite = "model")
    TypeMappings getTypeMappings();

    @Contains(opposite = "model")
    CustomRule[] getCustomRules();
}

interface CustomRule extends WithText {
    @Container(opposite = "customRules")
    GrammarModel getModel();
}

@Immutable
interface CodeRange {
    CodeLocation getStart();
    CodeLocation getStop();
    int getLength();
}

@Immutable
interface CodeLocation {
    int getIndex();
    int getLine();
    int getCharPosInLine();
}

@InterfaceOnly
interface LangElement {

}

@InterfaceOnly
interface CodeElement {
    CodeRange getCodeRange();
}

@InterfaceOnly
interface WithType extends LangElement {
    Type getType();
}

@InterfaceOnly
interface WithName extends LangElement {

    @GetterOnly
    String getName();


// TODO find out why Velocity does not look for methods in super interface
//    @DelegateTo(className = "eu.mihosoft.vmf.lang.grammar.NameDelegate")
//    String nameWithUpper();
}

@Immutable
interface Type extends LangElement, WithName {

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeNameDelegate")
    // String nameWithUpper();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeNameDelegate")
    // String asModelTypeName();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeNameDelegate")
    // String asJavaTypeNameNoCollections();

    String getPackageName();

    String getAntlrRuleName();

    boolean isRuleType();

    boolean isArrayType();
}

// @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.CheckPropertiesDelegate")
interface RuleClass extends WithName, CodeElement {

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.NameDelegate")
    // String nameWithUpper();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.NameDelegate")
    // String nameWithLower();

    @Container(opposite = "ruleClasses")
    GrammarModel getModel();

    @Contains(opposite = "parent")
    Property[] getProperties();

    @Contains(opposite = "parent")
    Property[] getCustomProperties();

    @Refers(opposite = "childClasses")
    RuleClass getSuperClass();

    String[] getSuperInterfaces();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.StringUtilDelegate")
    // String superInterfacesString();

    @Refers(opposite = "superClass")
    RuleClass[] getChildClasses();

    boolean isRoot();

    @Contains(opposite = "parent")
    DelegationMethod[] getDelegationMethods();

    @Contains(opposite = "parent")
    RuleAnnotation[] getCustomRuleAnnotations();

}

interface Property extends WithName, WithType, CodeElement {
    @Container(/*opposite = "properties"*/)
    RuleClass getParent();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.NameDelegate")
    // String nameWithUpper();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.NameDelegate")
    // String nameWithLower();

    Type getType();

    @Contains(opposite = "property")
    PropertyAnnotation[] getAnnotations();
}

interface DelegationMethod extends WithText{
    @Container(opposite = "delegationMethods")
    RuleClass getParent();
}

@InterfaceOnly
interface WithText {
    String getText();
}

interface PropertyAnnotation extends WithText {

    @Container(opposite = "annotations")
    Property getProperty();
}

interface RuleAnnotation extends WithText{
    @Container(opposite = "customRuleAnnotations")
    RuleClass getParent();
}

// TypeMapping

interface TypeMappings {
    @Contains(opposite = "parent")
    TypeMapping[] getTypeMappings();

    @Container(opposite = "typeMappings")
    GrammarModel getModel();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeMappingsLookup")
    // public Optional<Mapping> mappingByRuleName(String containeRuleName, String name);

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeMappingsLookup")
    // boolean mappingByRuleNameExists(String containeRuleName, String name);

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeMappingsLookup")
    // public String targetTypeNameOfMapping(String containeRuleName, String name);

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeMappingsLookup")
    // public String conversionCodeOfMappingStringToType(String containeRuleName, String name);

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeMappingsLookup")
    // public String conversionCodeOfMappingTypeToString(String containeRuleName, String name);

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeMappingsLookup")
    // public String defaultValueCode(String containerRuleName, String paramRuleName);

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeMappingsLookup")
    // public List<Mapping> mappingsByRuleName(String ruleName);
}

interface TypeMapping {

    @Container(opposite = "typeMappings")
    TypeMappings getParent();

    @Contains(opposite = "parent")
    Mapping[] getEntries();

    // @DelegateTo(className = "eu.mihosoft.vmf.vmftext.grammar.TypeMappingLookup")
    // public Optional<Mapping> mappingByRuleName(String name);

    String[] getApplyToNames();


}

interface Mapping {
    @Container(opposite="entries")
    TypeMapping getParent();

    String getRuleName();

    String getTypeName();

    String getTypeToStringCode();

    String getStringToTypeCode();

    String getDefaultValueCode();
}










