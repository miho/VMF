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
import eu.mihosoft.ext.apted.node.Node;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.*;
import org.junit.Assert;
import org.junit.Test;

public class CostModelTest {
    public static VObject test0;
    public static VObject test1;
    public static VObject test4;
    public static VObject test5;

    public static NodeDataCostModel costModel;
    public static Node<NodeData> t0;
    public static Node<NodeData> t1;
    public static Node<NodeData> t4;
    public static Node<NodeData> t5;

    public static void init(){
        diffing.NodeTestObject.main();
        test0 = diffing.NodeTestObject.testObject0; //{A{B}{C}}
        test1 = diffing.NodeTestObject.testObject1; //{A{B{D}{E}}{C}}
        test4 = diffing.NodeTestObject.testObject4; //{X{B}{C}}
        test5 = diffing.NodeTestObject.testObject5; //{X{Y}{Z}}

        NodeData[] indexTable0 = GenerateNodeTable.generate(test0.vmf());
        NodeData[] indexTable1 = GenerateNodeTable.generate(test1.vmf());
        NodeData[] indexTable4 = GenerateNodeTable.generate(test4.vmf());
        NodeData[] indexTable5 = GenerateNodeTable.generate(test5.vmf());

        t0 = Tree2NodeData.tree2NodeData(indexTable0);
        t1 = Tree2NodeData.tree2NodeData(indexTable1);
        t4 = Tree2NodeData.tree2NodeData(indexTable4);
        t5 = Tree2NodeData.tree2NodeData(indexTable5);
        costModel = new NodeDataCostModel();
    }

    /*
        Testing Object: {A{B}{C}} and {A{B{D}{E}}{C}}
        Simple cost-check for deletion/insertion.
    */
    @Test
    public void myCostModelTest0() {
        init();
        Assert.assertEquals(costModel.del(t0),1.0, 0.01);
        Assert.assertEquals(costModel.ins(t0),1.0, 0.01);

        Assert.assertEquals(costModel.del(t1),1.0, 0.01);
        Assert.assertEquals(costModel.ins(t1),1.0, 0.01);
    }

    /*
        Simple renaming cost-check. Renaming one property costs 0.2, we only look
        at the root node.
        Testing: {A{B}{C}} and {X{B}{C}} -> Should cost 0.2
        Testing: {X{B}{C}} and {X{Y}{Z}} -> Should cost 0.0
    */
    @Test
    public void myCostModelTest1() {
        init();
        Assert.assertEquals(costModel.ren(t0,t4), 0.2, 0.01);
        Assert.assertEquals(costModel.ren(t4,t5), 0.0, 0.01);
    }
}
