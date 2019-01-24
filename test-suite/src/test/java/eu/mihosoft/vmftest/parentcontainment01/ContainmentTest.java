/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmftest.parentcontainment01;

import org.junit.Assert;
import org.junit.Test;

public class ContainmentTest {
    @Test
    public void testContainmentBehaviorGetParent() {
        OperatorExpression operatorExpression = OperatorExpression.newInstance();

        NumberExpression leftValue = NumberExpression.newBuilder().withValue(3.2).build();
        NumberExpression rightValue = NumberExpression.newBuilder().withValue(1.2).build();

        System.out.println("left parent before: " + leftValue.getParent());
        System.out.println("right parent before: " + rightValue.getParent());

        Assert.assertEquals(null, operatorExpression.getParent());
        Assert.assertEquals(null, leftValue.getParent());
        Assert.assertEquals(null, rightValue.getParent());

        operatorExpression.setLeft(leftValue);
        operatorExpression.setRight(rightValue);

        System.out.println("left parent after: " + leftValue.getParent());
        System.out.println("right parent after: " + rightValue.getParent());

        Assert.assertEquals(null, operatorExpression.getParent());
        Assert.assertEquals(operatorExpression, leftValue.getParent());
        Assert.assertEquals(operatorExpression, rightValue.getParent());

    }

    @Test
    public void testContainmentBehaviorFindRoot() {
        OperatorExpression root = OperatorExpression.newInstance();

        NumberExpression l0   = NumberExpression.newBuilder().withValue(3.2).build();
        OperatorExpression r0 = OperatorExpression.newInstance();

        root.setLeft(l0);
        root.setRight(r0);

        Assert.assertEquals(root, l0.root());
        Assert.assertEquals(root, r0.root());

        NumberExpression l1 = NumberExpression.newBuilder().withValue(1.2).build();
        NumberExpression r1 = NumberExpression.newBuilder().withValue(5.6).build();

        r0.setLeft(l1);
        r0.setRight(r1);

        Assert.assertEquals(root, l1.root());
        Assert.assertEquals(root, r1.root());
    }
}
