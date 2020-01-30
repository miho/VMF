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

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmftest.diffing.vmfmodel.TreeNode;
import eu.mihosoft.vmftest.diffing.vmfmodel.Trait;
import eu.mihosoft.vmftest.annotations.AnnotatedModel;

public class NodeTestObject {
    public static VObject testObject0;
    public static VObject testObject1;
    public static VObject testObject2;
    public static VObject testObject3;
    public static VObject testObject4;
    public static VObject testObject5;
    public static VObject testObject6;
    public static VObject testObject7;
    public static VObject testObject8;
    public static VObject testObject9;
    public static VObject testObject10;
    public static VObject testObject0WithTrait;
    public static VObject testObject1WithTrait;
    public static VObject testObject2WithTrait;
    public static VObject testObject3WithTrait;

    public static void main() {
        //testObject0:  {A{B}{C}}
        TreeNode nodeA0 = TreeNode.newBuilder().withName("A").build();
        TreeNode nodeB0 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC0 = TreeNode.newBuilder().withName("C").build();
        nodeA0.getChildren().add(nodeB0);
        nodeA0.getChildren().add(nodeC0);
        testObject0 = nodeA0;

        //testObject1:  {A{B{D}{E}}{C}}
        TreeNode nodeA1 = TreeNode.newBuilder().withName("A").build();
        TreeNode nodeB1 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC1 = TreeNode.newBuilder().withName("C").build();
        TreeNode nodeD1 = TreeNode.newBuilder().withName("D").build();
        TreeNode nodeE1 = TreeNode.newBuilder().withName("E").build();
        nodeA1.getChildren().add(nodeB1);
        nodeA1.getChildren().add(nodeC1);
        nodeB1.getChildren().add(nodeD1);
        nodeB1.getChildren().add(nodeE1);
        testObject1 = nodeA1;

        //testObject2:  {A{B{C}}{B{C}}} - Clones
        TreeNode nodeA2 = TreeNode.newBuilder().withName("A").build();
        TreeNode nodeB2 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC2 = TreeNode.newBuilder().withName("C").build();
        TreeNode nodeB2_clone = TreeNode.newBuilder().withName("E").build();
        TreeNode nodeC2_clone = TreeNode.newBuilder().withName("F").build();
        nodeA2.getChildren().add(nodeB2);
        nodeA2.getChildren().add(nodeB2_clone);
        nodeB2.getChildren().add(nodeC2);
        nodeB2_clone.getChildren().add(nodeC2_clone);
        testObject2 = nodeA2;

        //testObject3:  {B{D}}
        TreeNode nodeB3 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeD3 = TreeNode.newBuilder().withName("D").build();
        nodeB3.getChildren().add(nodeD3);
        testObject3 = nodeB3;

        //testObject4:  {X{B}{C}}
        TreeNode nodeX4 = TreeNode.newBuilder().withName("X").build();
        TreeNode nodeB4 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC4 = TreeNode.newBuilder().withName("C").build();
        nodeX4.getChildren().add(nodeB4);
        nodeX4.getChildren().add(nodeC4);
        testObject4 = nodeX4;

        //testObject5:  {X{Y}{Z}}
        TreeNode nodeX5 = TreeNode.newBuilder().withName("X").build();
        TreeNode nodeY5 = TreeNode.newBuilder().withName("Y").build();
        TreeNode nodeZ5 = TreeNode.newBuilder().withName("Z").build();
        nodeX5.getChildren().add(nodeY5);
        nodeX5.getChildren().add(nodeZ5);
        testObject5 = nodeX5;

        //testObject6:  {B{C{D}}}
        TreeNode nodeB6 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC6 = TreeNode.newBuilder().withName("C").build();
        TreeNode nodeD6 = TreeNode.newBuilder().withName("D").build();
        nodeB6.getChildren().add(nodeC6);
        nodeC6.getChildren().add(nodeD6);
        testObject6 = nodeB6;

        //testObject7:  {X{B{C{D}}}
        TreeNode nodeX7 = TreeNode.newBuilder().withName("X").build();
        TreeNode nodeB7 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC7 = TreeNode.newBuilder().withName("C").build();
        TreeNode nodeD7 = TreeNode.newBuilder().withName("D").build();
        nodeX7.getChildren().add(nodeB7);
        nodeB7.getChildren().add(nodeC7);
        nodeC7.getChildren().add(nodeD7);
        testObject7 = nodeX7;

        //testObject8:  {X{B{C{D}{E}}}
        TreeNode nodeX8 = TreeNode.newBuilder().withName("X").build();
        TreeNode nodeB8 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC8 = TreeNode.newBuilder().withName("C").build();
        TreeNode nodeD8 = TreeNode.newBuilder().withName("D").build();
        TreeNode nodeE8 = TreeNode.newBuilder().withName("E").build();
        nodeX8.getChildren().add(nodeB8);
        nodeB8.getChildren().add(nodeC8);
        nodeC8.getChildren().add(nodeD8);
        nodeC8.getChildren().add(nodeE8);
        testObject8 = nodeX8;

        //testObject9:  {A{B{X}}{D{E}}}
        TreeNode nodeA9 = TreeNode.newBuilder().withName("A").build();
        TreeNode nodeB9 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeX9 = TreeNode.newBuilder().withName("X").build();
        TreeNode nodeD9 = TreeNode.newBuilder().withName("D").build();
        TreeNode nodeE9 = TreeNode.newBuilder().withName("E").build();
        nodeA9.getChildren().add(nodeB9);
        nodeA9.getChildren().add(nodeD9);
        nodeB9.getChildren().add(nodeX9);
        nodeD9.getChildren().add(nodeE9);
        testObject9 = nodeA9;

        //testObject10:  {A{B}{X{C}}}
        TreeNode nodeA10 = TreeNode.newBuilder().withName("A").build();
        TreeNode nodeB10 = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC10 = TreeNode.newBuilder().withName("C").build();
        TreeNode nodeX10 = TreeNode.newBuilder().withName("X").build();
        nodeA10.getChildren().add(nodeB10);
        nodeA10.getChildren().add(nodeX10);
        nodeX10.getChildren().add(nodeC10);
        testObject10 = nodeA10;


        //testObject0WithTrait:  {A{B}{C}}
        //Traits for A: Name="TraitA0", Trait="Test0"
        //Traits for A: Name="TraitA1", Trait="Test1"
        //Traits for B: Name="TraitB0", Trait="Test0"
        //Traits for C: Name="TraitC0", Trait="Test0"
        TreeNode nodeA0WithTraits = TreeNode.newBuilder().withName("A").build();
        TreeNode nodeB0WithTraits = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC0WithTraits = TreeNode.newBuilder().withName("C").build();
        nodeA0WithTraits.getChildren().add(nodeB0WithTraits);
        nodeA0WithTraits.getChildren().add(nodeC0WithTraits);
        Trait traitA0_object0 = Trait.newBuilder().withName("TraitA0").withTrait("Test0").build();
        Trait traitA1_object0 = Trait.newBuilder().withName("TraitA1").withTrait("Test1").build();
        Trait traitB0_object0 = Trait.newBuilder().withName("TraitB0").withTrait("Test0").build();
        Trait traitC0_object0 = Trait.newBuilder().withName("TraitC0").withTrait("Test0").build();
        nodeA0WithTraits.getTraits().add(traitA0_object0);
        nodeA0WithTraits.getTraits().add(traitA1_object0);
        nodeB0WithTraits.getTraits().add(traitB0_object0);
        nodeC0WithTraits.getTraits().add(traitC0_object0);
        testObject0WithTrait = nodeA0WithTraits;

        //testObject1WithTrait:  {A{B{D}{E}}{C}}
        //Traits for A: Name="TraitA0", Trait="Test0"
        //Traits for A: Name="TraitA1", Trait="Test1"
        //Traits for B: Name="TraitB0", Trait="Test0"
        //Traits for E: Name="TraitE0", Trait="Test0"
        TreeNode nodeA1WithTraits = TreeNode.newBuilder().withName("A").build();
        TreeNode nodeB1WithTraits = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC1WithTraits = TreeNode.newBuilder().withName("C").build();
        TreeNode nodeD1WithTraits = TreeNode.newBuilder().withName("D").build();
        TreeNode nodeE1WithTraits = TreeNode.newBuilder().withName("E").build();
        nodeA1WithTraits.getChildren().add(nodeB1WithTraits);
        nodeA1WithTraits.getChildren().add(nodeC1WithTraits);
        nodeB1WithTraits.getChildren().add(nodeD1WithTraits);
        nodeB1WithTraits.getChildren().add(nodeE1WithTraits);
        Trait traitA0_object1 = Trait.newBuilder().withName("TraitA0").withTrait("Test0").build();
        Trait traitA1_object1  = Trait.newBuilder().withName("TraitA1").withTrait("Test1").build();
        Trait traitB0_object1  = Trait.newBuilder().withName("TraitB0").withTrait("Test0").build();
        Trait traitE0_object1  = Trait.newBuilder().withName("TraitE0").withTrait("Test0").build();
        nodeA0WithTraits.getTraits().add(traitA0_object1);
        nodeA0WithTraits.getTraits().add(traitA1_object1);
        nodeB0WithTraits.getTraits().add(traitB0_object1);
        nodeC0WithTraits.getTraits().add(traitE0_object1);
        testObject1WithTrait = nodeA1WithTraits;

        //testObject2WithTraits:  {A{B{C}}{B{C}}}
        //Traits for B left: Name="TraitBLeft", Trait="Left"
        //Traits for B right: Name="TraitBRight", Trait="Right"
        TreeNode nodeA2WithTraits = TreeNode.newBuilder().withName("A").build();
        TreeNode nodeB2WithTraits = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC2WithTraits = TreeNode.newBuilder().withName("C").build();
        TreeNode nodeB2_cloneWithTraits = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeC2_cloneWithTraits = TreeNode.newBuilder().withName("C").build();
        nodeA2WithTraits.getChildren().add(nodeB2WithTraits);
        nodeA2WithTraits.getChildren().add(nodeB2_cloneWithTraits);
        nodeB2WithTraits.getChildren().add(nodeC2WithTraits);
        nodeB2_cloneWithTraits.getChildren().add(nodeC2_cloneWithTraits);
        Trait traitBLeft_object3  = Trait.newBuilder().withName("TraitBLeft").withTrait("Left").build();
        Trait traitBRight_object3  = Trait.newBuilder().withName("TraitRight").withTrait("Right").build();
        nodeB2WithTraits.getTraits().add(traitBLeft_object3);
        nodeB2_cloneWithTraits.getTraits().add(traitBRight_object3);
        testObject2WithTrait = nodeA2WithTraits;

        //testObject3WithTrait:  {B{D}}
        //Traits for B: Name="TraitB0", Trait="Test0"
        //Traits for D: Name="TraitD0", Trait="Test0"
        //Traits for D: Name="TraitD1", Trait="Test1"
        //Traits for D: Name="TraitD2", Trait="Test2"
        TreeNode nodeB3WithTraits = TreeNode.newBuilder().withName("B").build();
        TreeNode nodeD3WithTraits = TreeNode.newBuilder().withName("D").build();
        nodeB3WithTraits.getChildren().add(nodeD3WithTraits);
        Trait traitB0_object3 = Trait.newBuilder().withName("TraitB0").withTrait("Test0").build();
        Trait traitD0_object3  = Trait.newBuilder().withName("TraitD0").withTrait("Test0").build();
        Trait traitD1_object3  = Trait.newBuilder().withName("TraitD1").withTrait("Test1").build();
        Trait traitD2_object3  = Trait.newBuilder().withName("TraitD2").withTrait("Test2").build();
        nodeB3WithTraits.getTraits().add(traitB0_object3);
        nodeD3WithTraits.getTraits().add(traitD0_object3);
        nodeD3WithTraits.getTraits().add(traitD1_object3);
        nodeD3WithTraits.getTraits().add(traitD2_object3);
        testObject3WithTrait = nodeB3WithTraits;
    }
}
