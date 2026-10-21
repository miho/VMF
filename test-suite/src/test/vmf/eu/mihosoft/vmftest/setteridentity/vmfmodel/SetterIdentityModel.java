/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmftest.setteridentity.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.VMFEquals;
import eu.mihosoft.vmf.core.VMFModel;

/**
 * A small syntax-tree-like model with structural equality: the container
 * property is declared once in the base type and every child property names
 * it as its opposite (as a model generated from a grammar does).
 */
@VMFModel(equality = VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL)
@InterfaceOnly
interface Element {
    /**
     * The element this element is contained in. All child properties of the
     * model declare this property as their opposite (multiple opposites, not
     * known at compile time).
     */
    @Container
    Element getParent();
}

@InterfaceOnly
interface Expression extends Element {
}

interface NumberExpression extends Expression {
    double getValue();
}

interface BinaryExpression extends Expression {
    String getOperator();

    @Contains(opposite = "parent")
    Expression getLeft();

    @Contains(opposite = "parent")
    Expression getRight();

    @Contains(opposite = "parent")
    Expression[] getArguments();
}

/**
 * Holds a plain (non-containment) reference to a number expression.
 */
interface Holder {
    NumberExpression getValue();
}
