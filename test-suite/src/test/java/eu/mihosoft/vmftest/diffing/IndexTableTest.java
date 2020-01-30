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

package eu.mihosoft.vmftest.diffing;

import eu.mihosoft.vmf.runtime.core.Diffing;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.*;
import diffing.NodeTestObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Assert;
import org.junit.rules.ExpectedException;

public class IndexTableTest {
    private static NodeData[] nodeTable0;
    private static NodeData[] nodeTable1;
    private static NodeData[] nodeTable2;
    private static NodeData[] nodeTable3;
    private static NodeData[] nodeTable0Traits;
    private static VObject test0;
    private static VObject test1;

    private static void init(){
        NodeTestObject.main();
        test0 = diffing.NodeTestObject.testObject0; //{A{B}{C}}
        test1 = NodeTestObject.testObject1; //{A{B{D}{E}}{C}}
        VObject test2 = NodeTestObject.testObject2; //{A{B{C}}{B{C}}} - Clones
        VObject test3 = NodeTestObject.testObject3; //{A{B{C}}{B{C}}} - No clones
        VObject test0Traits = NodeTestObject.testObject0WithTrait; //{A{B}{C}} w\Traits

        nodeTable0 = GenerateNodeTable.generate(test0.vmf());
        nodeTable1 = GenerateNodeTable.generate(test1.vmf());
        nodeTable2 = GenerateNodeTable.generate(test2.vmf());
        nodeTable3 = GenerateNodeTable.generate(test3.vmf());
        nodeTable0Traits = GenerateNodeTable.generate(test0Traits.vmf());
    }

    /*
        Testing Object: {A{B}{C}} and {A{B{D}{E}}{C}}
        Simple Object check.
    */
    @Test
    public void myIndexTest0() {
        init();
        Assert.assertEquals(nodeTable0[0].getObject(), test0);
        Assert.assertEquals(nodeTable1[0].getObject(), test1);
    }

    /*
        Testing Object: {A{B}{C}}
        Simple containment check.
    */
    @Test
    public void myIndexTest1() {
        init();
        Assert.assertEquals(nodeTable0[0].getContainment(), "");
        Assert.assertEquals(nodeTable0[1].getContainment(), "children");
        Assert.assertEquals(nodeTable0[2].getContainment(), "children");
    }

    /*
        Testing Object: {A{B{D}{E}}{C}}
        Simple test for parent-child relation in the containment tree.
    */
    @Test
    public void myIndexTest2() {
        init();
        Assert.assertEquals(nodeTable1[0].getParent(), -1); //A
        Assert.assertEquals(nodeTable1[1].getParent(), 0); //B
        Assert.assertEquals(nodeTable1[2].getParent(), 1); //D
        Assert.assertEquals(nodeTable1[3].getParent(), 1); //E
        Assert.assertEquals(nodeTable1[4].getParent(), 0); //C
    }

    /*
        Testing Object: {A{B}{C}} with additional Traits
        This test should show that the traits are set correctly.
    */
    @Test
    public void myIndexTest3() {
        init();
        Trait trait0 = ((TreeNode) nodeTable0Traits[0].getObject()).getTraits().get(0);
        Trait trait1 = ((TreeNode) nodeTable0Traits[0].getObject()).getTraits().get(1);
        Assert.assertEquals(trait0.getName(), "TraitA0");
        Assert.assertEquals(trait1.getName(), "TraitA1");
        Assert.assertEquals(trait0.getTrait(), "Test0");
        Assert.assertEquals(trait1.getTrait(), "Test1");
    }

    /*
        Testing Object: {A{B}{C}} with additional Traits
        This test should show that different containments are identified correctly.
    */
    @Test
    public void myIndexTest4() {
        init();
        Trait trait0 = ((TreeNode) nodeTable0Traits[0].getObject()).getTraits().get(0);
        Trait trait1 = ((TreeNode) nodeTable0Traits[0].getObject()).getTraits().get(1);

        Assert.assertEquals(trait0.getAffiliation(), nodeTable0Traits[0].getObject());
        Assert.assertEquals(trait1.getAffiliation(), nodeTable0Traits[0].getObject());

        Assert.assertEquals(nodeTable0Traits[0].getContainment(), "");
        Assert.assertEquals(nodeTable0Traits[1].getContainment(), "children");
        Assert.assertEquals(nodeTable0Traits[2].getContainment(), "trait");
        Assert.assertEquals(nodeTable0Traits[3].getContainment(), "children");
        Assert.assertEquals(nodeTable0Traits[4].getContainment(), "trait");
        Assert.assertEquals(nodeTable0Traits[5].getContainment(), "trait");
        Assert.assertEquals(nodeTable0Traits[6].getContainment(), "trait");
    }

    /*
        Testing Object: {A{B{C}}{B{C}}}
        The Elements B and C are not identical Objects. Test if treated as such.
     */
    @Test
    public void myIndexTest5() {
        init();
        Assert.assertEquals(nodeTable2[0].getParent(), -1);
        Assert.assertEquals(nodeTable2[1].getParent(), 0);
        Assert.assertEquals(nodeTable2[2].getParent(), 1);
        Assert.assertEquals(nodeTable2[3].getParent(), 0);
        Assert.assertEquals(nodeTable2[4].getParent(), 3);
    }

    /*
        Testing Object: {A{B{C}}{B{C}}}
        The Elements B and C are the same Objects. They should not appear in the containment tree
        a second time.
     */
    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Test
    public void myIndexTest6() {
        init();
        Assert.assertEquals(nodeTable3.length, 3);
        Assert.assertEquals(nodeTable3[0].getParent(), -1);
        Assert.assertEquals(nodeTable3[1].getParent(), 0);
        Assert.assertEquals(nodeTable3[2].getParent(), 1);

        exception.expect(ArrayIndexOutOfBoundsException.class);
        Assert.assertNull(nodeTable3[3]);
        Assert.assertNull(nodeTable3[4]);
    }

    /*
        Testing Object: {A{B{D}{E}}{C}}
        Test the reordering of the indexTable objects in InOrder.
        Original Ordering: A,B,D,E,C
        InOrder: D,E,B,C,A
    */
    @Test
    public void myInorderTest0() {
        init();
        NodeData[] inOrder1 = GenerateNodeTable.transform_inOrder(nodeTable1);

        Assert.assertEquals(inOrder1[0].getParent(), 1); //D
        Assert.assertEquals(inOrder1[1].getParent(), 1); //E
        Assert.assertEquals(inOrder1[2].getParent(), 0); //B
        Assert.assertEquals(inOrder1[3].getParent(), 0); //C
        Assert.assertEquals(inOrder1[4].getParent(), -1); //A

        Assert.assertEquals(((TreeNode)inOrder1[0].getObject()).getName(), "D");
        Assert.assertEquals(((TreeNode)inOrder1[1].getObject()).getName(), "E");
        Assert.assertEquals(((TreeNode)inOrder1[2].getObject()).getName(), "B");
        Assert.assertEquals(((TreeNode)inOrder1[3].getObject()).getName(), "C");
        Assert.assertEquals(((TreeNode)inOrder1[4].getObject()).getName(), "A");
    }
}
