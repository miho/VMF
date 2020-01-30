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

import eu.mihosoft.vmf.runtime.core.internal.*;
import diffing.NodeTestObject;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.Assert;
import org.junit.Test;

public class MappingTest {
    /*
    Testing if Objects equal after making changes.
    Tested on all combinations of test Objects provided by the NodeTestObjects class.
    */

    // MAP 0 -->
    // _______________________________
    @Test
    public void testMapping0To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 1 -->
    // _______________________________

    @Test
    public void testMapping1To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 2 -->
    // _______________________________

    @Test
    public void testMapping2To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 3 -->
    // _______________________________

    @Test
    public void testMapping3To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 4 -->
    // _______________________________

    @Test
    public void testMapping4To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping4To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject4;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 5 -->
    // _______________________________

    @Test
    public void testMapping5To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping5To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject5;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 6 -->
    // _______________________________

    @Test
    public void testMapping6To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping6To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject6;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 7 -->
    // _______________________________

    @Test
    public void testMapping7To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping7To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject7;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 8 -->
    // _______________________________

    @Test
    public void testMapping8To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping8To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject8;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 9 -->
    // _______________________________

    @Test
    public void testMapping9To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping9To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject9;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 10 -->
    // _______________________________

    @Test
    public void testMapping10To0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping10To3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject10;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 0Traits -->
    // _______________________________

    @Test
    public void testMapping0TraitsTo0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping0TraitsTo3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject0WithTrait;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 1Traits -->
    // _______________________________

    @Test
    public void testMapping1TraitsTo0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping1TraitsTo3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject1WithTrait;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 2Traits -->
    // _______________________________

    @Test
    public void testMapping2TraitsTo0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping2TraitsTo3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject2WithTrait;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    // MAP 3Traits -->
    // _______________________________

    @Test
    public void testMapping3TraitsTo0(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject0;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo1(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject1;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo2(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject2;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo3(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject3;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo4(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject4;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo5(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject5;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo6(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject6;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo7(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject7;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo8(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject8;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo9(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject9;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo10(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject10;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo0Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject0WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo1Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject1WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo2Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject2WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

    @Test
    public void testMapping3TraitsTo3Traits(){
        NodeTestObject.main();
        VObject original = NodeTestObject.testObject3WithTrait;
        VObject clone = NodeTestObject.testObject3WithTrait;
        InitChanges.init(original, clone);
        MakeChanges.printChanges();
        Assert.assertTrue(MakeChanges.applyChange());
    }

}

