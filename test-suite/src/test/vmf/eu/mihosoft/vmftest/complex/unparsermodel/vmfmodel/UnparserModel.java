
/*
 * Copyright 2017-2018 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2018 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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


interface UnparserModel {

    @Contains(opposite = "parent")
    UPRule[] getRules();

    @Contains(opposite = "parent")
    UPLexerRule[] getLexerRules();

}

interface UPLexerRule extends WithName, WithText {
    @Container(opposite = "lexerRules")
    UnparserModel getParent();
}

@InterfaceOnly
interface WithTokenLocation {
    @IgnoreEquals
    int getTokenIndexStart();
    @IgnoreEquals
    int getTokenIndexStop();
}

@InterfaceOnly
interface WithAltId {
    int getAltId();
}

@InterfaceOnly
interface WithElementId {
    int getElementId();
}

@InterfaceOnly
interface WithRuleId {
    int getRuleId();
}

@InterfaceOnly
interface UPRuleBase extends WithRuleId {
    @Contains(opposite = "parentRule")
    AlternativeBase[] getAlternatives();
}

interface UPRule extends WithName, UPRuleBase, WithTokenLocation {
    @Container(opposite = "rules")
    UnparserModel getParent();

    @IgnoreEquals
    int getTokenIndexCOLON();

    @IgnoreEquals
    int getTokenIndexLOCALS();
}

@InterfaceOnly
interface SubRule extends UPRuleBase {

}

@InterfaceOnly
interface AlternativeBase extends WithText, WithAltId {
    @Container(opposite = "alternatives")
    UPRuleBase getParentRule();

    @Contains(opposite = "parentAlt")
    UPElement[] getElements();
}

interface UPElement extends WithText, WithElementId, WithTokenLocation {
    @Container(opposite = "elements")
    AlternativeBase getParentAlt();


    boolean isListType();

    boolean isLexerRule();
    boolean isTerminal();
    boolean isParserRule();

    boolean isAction();

    boolean isNegated();

    String getRuleName();
}

interface UPNamedElement extends UPElement, WithName {

}

interface UPSubRuleElement extends UPElement, SubRule {

}

interface UPNamedSubRuleElement extends UPElement, SubRule, WithName {

}

interface Alternative extends AlternativeBase {

}

interface LabeledAlternative extends Alternative, WithName {

}



