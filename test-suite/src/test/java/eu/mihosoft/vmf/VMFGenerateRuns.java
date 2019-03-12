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
package eu.mihosoft.vmf;

import eu.mihosoft.vmf.testing.VMFTestShell;
import eu.mihosoft.vmftests.completepropertyordertest.vmfmodel.CompleteOrderInfo;
import eu.mihosoft.vmftests.completepropertyordertest.vmfmodel.IncompleteOrderInfo;
import eu.mihosoft.vmftests.completepropertyordertest.vmfmodel.InvalidOrderInfo;
import eu.mihosoft.vmftests.delegationtest.vmfmodel.DelegationTestClass;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.InheritedDefaultValue;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.InheritedDefaultValueFromTwoParents;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.InheritedDefaultValueFromTwoParents2;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.InheritedDefaultValueOverride;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.InheritedDefaultValueOverride2;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.InheritedDefaultValueParent;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.InheritedDefaultValueParent2;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.Node;
import eu.mihosoft.vmftests.reflectiontest.vmfmodel.ReflectionTest;
import eu.mihosoft.vmftests.test1.vmfmodel.DaBean;
import eu.mihosoft.vmftests.test2.vmfmodel.Child;
import eu.mihosoft.vmftests.test2.vmfmodel.Named;
import eu.mihosoft.vmftests.test2.vmfmodel.Parent;
import org.junit.Assert;
import org.junit.Test;

public class VMFGenerateRuns extends VMFTestShell {

    @Test
    public void testGetterSetterFeature() throws Throwable {
        setUp(DaBean.class);
        runScript("aDaBean.setName(\"testName\")");
        assertResult("aDaBean.getName()", "testName");

        String daBeanCode = findGeneratedCode("eu.mihosoft.vmftests.test1.DaBean");
        System.out.println(daBeanCode);

    }

    @Test
    public void testCloneFeature() throws Throwable {
        setUp(DaBean.class);
        runScript("aDaBean.setName(\"testName\")");
        runScript("cloneBean = aDaBean.clone()");
        assertResult("cloneBean.getName()", "testName");
    }

    @Test
    public void testReadOnlyFeature() throws Throwable {
        setUp(DaBean.class);
        runScript("roBean = aDaBean.asReadOnly()");
        assertExceptionOn("roBean.setName(\"test\")", "MissingMethodException");
    }

    @Test
    public void testContainerContainmentAddChild() throws Throwable {
        setUp(Named.class, Child.class, Parent.class);
        runScript("aParent.setName(\"Father\")");
        runScript("aParent.getChildren().add(aChild)");
        assertResult("aChild.getParent().getName()","Father");
        runScript("aChild.setName(\"Luke\")");
        assertResult("aParent.getChildren().get(0).getName()", "Luke");
        runScript("aParent.getChildren().clear()");
        assertResult("aChild.getParent()", null);
    }

    @Test
    public void testToStringFeatureSimple() throws Throwable {
        setUp(Named.class, Child.class, Parent.class);
        runScript("aParent.setName(\"Father\")");
        runScript("aParent.getChildren().add(aChild)");
        runScript("aChild.setName(\"Luke\")");
        runScript("println(aParent.toString())");
        assertResult("aParent.toString()","{\"@type\":\"Parent\", [{\"@type\":\"Child\", \"name\": \"Luke\"}], \"elements\": \"null\", \"name\": \"Father\"}");
    }

    @Test
    public void testDeepClone1() throws Throwable {
        setUp(Named.class, Child.class, Parent.class);
        runScript("aParent.setName(\"Father\")");
        runScript("aParent.getChildren().add(aChild)");
        runScript("aChild.setName(\"Luke\")");
        runScript("aCloneParent = aParent.vmf().content().deepCopy()");
        assertResult("java.util.Objects.equals(aParent, aCloneParent)", true);
        String str = (String) runScript("aParent.toString()");
        assertResult("aCloneParent.toString()", str);
    }

    @Test
    public void testMethodDelegation() throws Throwable {
        addCode("eu.mihosoft.vmftests.delegationtest.MyBehavior",

                "\n" +
                "package eu.mihosoft.vmftests.delegationtest;\n" +
                "public class MyBehavior implements eu.mihosoft.vmf.runtime.core.DelegatedBehavior<DelegationTestClass>{\n" +
                "\n" +
                "    private eu.mihosoft.vmftests.delegationtest.DelegationTestClass caller;\n" +
                "    private boolean constructorCalled;\n" +
                "\n" +
                "    @Override\n" +
                "    public void setCaller(DelegationTestClass caller) {\n" +
                "        this.caller = caller;\n" +
                "    }\n" +
                "\n" +
                "    public boolean nameStartsWith(String string) {\n" +
                "        if(string==null) {\n" +
                "            return false;\n" +
                "        }\n" +
                "\n" +
                "        return caller.getName().startsWith(string);\n" +
                "    }\n" +
                "\n" +
                "    public void onDelegationTestClassInstantiated() {\n" +
                "        constructorCalled = true;\n" +
                "    }\n" +
                "\n" +
                "    public boolean constructorCalled() {\n" +
                "        return this.constructorCalled;\n" +
                "    }\n" +
                "}\n");
        try {
            setUp(DelegationTestClass.class);
        } catch(Throwable ex) {
            String code = findGeneratedCode("eu.mihosoft.vmftests.delegationtest.DelegationTestClass");
            System.out.println(code);
            throw ex;
        }

        assertResult("aDelegationTestClass.constructorCalled()", true);
        runScript("aDelegationTestClass.setName(\"Father\")");
        assertResult("aDelegationTestClass.nameStartsWith(\"F\")", true);
        assertResult("aDelegationTestClass.nameStartsWith(\"G\")", false);

        String code = findGeneratedCode("eu.mihosoft.vmftests.delegationtest.DelegationTestClass");
        System.out.println(code);
    }

    @Test
    public void testReflectionSetUnsetPrimitiveWithCompiletimeDefault() throws Throwable {

        try {

            setUp(ReflectionTest.class, Node.class);

            // id must be equal to it's reflection property
            assertResult("aReflectionTest.getId()==aReflectionTest.vmf().reflect().propertyByName(\"id\").get().get()", true);

            // id must not be set
            assertResult("aReflectionTest.vmf().reflect().propertyByName(\"id\").get().isSet()", false);

            // default value of id is 23
            assertResult("aReflectionTest.getId()==23", true);

        } catch( Throwable tr ) {
            String code = findGeneratedCode("eu.mihosoft.vmftests.reflectiontest.impl.ReflectionTestImpl");
            System.err.println(code);
            throw tr;
        }

    }

    @Test
    public void testReflectionSetUnsetPrimitiveWithRuntimeDefault() throws Throwable {

        try {

            setUp(ReflectionTest.class, Node.class);

            // id2 is not set as well
            assertResult("aReflectionTest.vmf().reflect().propertyByName(\"id2\").get().isSet()", false);

            // if we set id2 ...
            runScript("aReflectionTest.setId2(\"test 123\");");

            // ... it should be set
            assertResult("aReflectionTest.vmf().reflect().propertyByName(\"id2\").get().isSet()", true);

            // if we set it to 'null' (the default value) ...
            runScript("aReflectionTest.setId2(null);");

            // ... it should not be set
            assertResult("aReflectionTest.vmf().reflect().propertyByName(\"id2\").get().isSet()", false);

            // we should check per instance default values:
            runScript("aReflectionTest.vmf().reflect().propertyByName(\"id2\").get().setDefault(\"abc\")");

            // the default value should be updated, so it should not be set
            assertResult("aReflectionTest.vmf().reflect().propertyByName(\"id2\").get().isSet()", false);

            // ... but the value should be "abc" instead of "null"
            assertResult("\"abc\".equals(aReflectionTest.getId2())", true);


        } catch( Throwable tr ) {
            String code = findGeneratedCode("eu.mihosoft.vmftests.reflectiontest.impl.ReflectionTestImpl");
            System.err.println(code);
            throw tr;
        }

    }


    @Test
    public void testReflectionSetUnsetCollectionWithCompiletimeDefault() throws Throwable {

        try {

            setUp(ReflectionTest.class, Node.class);

            // values is not set
            assertResult("aReflectionTest.vmf().reflect().propertyByName(\"values\").get().isSet()", false);

            // (but is not null because of its default value, size==3)
            assertResult("aReflectionTest.getValues().size()==3", true);

        } catch( Throwable tr ) {
            String code = findGeneratedCode("eu.mihosoft.vmftests.reflectiontest.impl.ReflectionTestImpl");
            System.err.println(code);
            throw tr;
        }

    }

    @Test
    public void testReflectionSetUnsetContainmentProperties() throws Throwable {

        try {

            setUp(ReflectionTest.class, Node.class);

            // containment properties cannot be set. we expect unset as default:
            assertResult("aNode.vmf().reflect().propertyByName(\"parent\").get().isSet()", false);

            // containment properties cannot be set. we expect an exception (for default values):
            assertExceptionOn("aNode.vmf().reflect().propertyByName(\"parent\").get().setDefault(aNode)",
                    "RuntimeException");

        } catch( Throwable tr ) {
            String code = findGeneratedCode("eu.mihosoft.vmftests.reflectiontest.impl.ReflectionTestImpl");
            System.err.println(code);
            throw tr;
        }

    }

    @Test
    public void testReflectionSetUnsetReadOnlyProperties() throws Throwable {

        try {

            setUp(ReflectionTest.class, Node.class);

            // obtain a readonly reference of the object:
            runScript("aReflectionTestRO = aReflectionTest.asReadOnly()");



            // containment properties cannot be set. we expect an exception:
            assertExceptionOn("aReflectionTestRO.vmf().reflect().propertyByName(\"id\").get().set(24)",
                    "RuntimeException");

            // containment properties cannot be set. we expect an exception (also for default values):
            assertExceptionOn("aReflectionTestRO.vmf().reflect().propertyByName(\"id\").get().setDefault(25)",
                    "RuntimeException");

        } catch( Throwable tr ) {
            String code = findGeneratedCode("eu.mihosoft.vmftests.reflectiontest.impl.ReadOnlyReflectionTestImpl");
            System.err.println(code);
            throw tr;
        }

    }

    @Test
    public void testInheritedDefaultValue() throws Throwable {

        try {

            setUp(InheritedDefaultValueParent.class, InheritedDefaultValue.class, InheritedDefaultValueOverride.class, InheritedDefaultValueOverride2.class);

            // default should be set
            assertResult("aInheritedDefaultValueParent.getMyValue()", 123);

            // for inherited as well
            assertResult("aInheritedDefaultValue.getMyValue()", 123);

        } catch( Throwable tr ) {
            //String code = findGeneratedCode("eu.mihosoft.vmftests.reflectiontest.impl.ReadOnlyReflectionTestImpl");
            //System.err.println(code);
            throw tr;
        }
    }

    @Test
    public void testInheritedDefaultValueWithOverride() throws Throwable {

        try {

            setUp(InheritedDefaultValueParent.class, InheritedDefaultValue.class, InheritedDefaultValueOverride.class, InheritedDefaultValueOverride2.class);

            // for override we expect a different default value
            assertResult("aInheritedDefaultValueOverride.getMyValue()", -123);

            // for override2 we expect a the default value of int since the feature was redeclared
            assertResult("aInheritedDefaultValueOverride2.getMyValue()", 0);


        } catch( Throwable tr ) {
            //String code = findGeneratedCode("eu.mihosoft.vmftests.reflectiontest.impl.ReadOnlyReflectionTestImpl");
            //System.err.println(code);
            throw tr;
        }

    }

    @Test
    public void testInheritedDefaultValueMultipleParents() throws Throwable {

        try {

            setUp(
             InheritedDefaultValueParent.class,
             InheritedDefaultValueParent2.class,
             InheritedDefaultValueFromTwoParents.class,
             InheritedDefaultValueFromTwoParents2.class
            );

            // default should be set to inherited default of first interface (order matters in extends I1, I2, ...)
            assertResult("aInheritedDefaultValueFromTwoParents.getMyValue()", 123);

            // default should be set to inherited default of first interface (order matters in extends I1, I2, ...)
            assertResult("aInheritedDefaultValueFromTwoParents2.getMyValue()", 456);

        } catch( Throwable tr ) {
            //String code = findGeneratedCode("eu.mihosoft.vmftests.reflectiontest.impl.ReadOnlyReflectionTestImpl");
            //System.err.println(code);
            throw tr;
        }
    }

    @Test
    public void testCompleteOrderInfoIsValidTest() throws Throwable {
        try {
            setUp(CompleteOrderInfo.class);
        } catch (Exception ex) {
            Assert.fail("Must not throw an exception");
        }
    }

    @Test
    public void testIncompleteOrderInfoIsInvalidTest() throws Throwable {
        try {
            setUp(IncompleteOrderInfo.class);
            Assert.fail("Should throw an exception");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testInvalidOrderInfoTest() throws Throwable {
        try {
            setUp(InvalidOrderInfo.class);
            Assert.fail("Should throw an exception");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testImmutabilityInvalidMutableInheritance() throws Throwable {
        try {

            addModelCode("mutableinheritance.vmfmodel.MutableInheritanceBase",
            "package mutableinheritance.vmfmodel;\n"+
            "import eu.mihosoft.vmf.core.*;\n"+
            "public interface MutableInheritanceBase {\n"+
            "    String getName();\n"+
            "}");
            addModelCode("mutableinheritance.vmfmodel.MutableInheritanceImmutable",
            "package mutableinheritance.vmfmodel;\n"+
            "import eu.mihosoft.vmf.core.*;\n"+
            "@Immutable public interface MutableInheritanceImmutable extends MutableInheritanceBase {}");

            setupModelFromCode();

            Assert.fail("Should throw an exception");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testImmutabilityInvalidMutableProperty() throws Throwable {
        try {
            addModelCode("mutableproperties.vmfmodel.MutableProperty",
            "package mutableproperties.vmfmodel;\n" + 
            "public interface MutableProperty {\n"+
            "    String getName();\n"+
            "}"
            );
            addModelCode("mutableproperties.vmfmodel.MutablePropertyImmutable",
            "package mutableproperties.vmfmodel;\n"+
            "import eu.mihosoft.vmf.core.*;\n"+
            "@Immutable\n"+
            "public interface MutablePropertyImmutable {\n"+
            "    MutableProperty getProperty();\n"+
            "}"
            );
            setupModelFromCode();
            Assert.fail("Should throw an exception");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testImmutabilityValidInterfaceOnlyGetterOnlyProperty() throws Throwable {
        try {
            addModelCode("getteronlypropvalid.vmfmodel.MyProperty",
            "package getteronlypropvalid.vmfmodel;\n" + 
            "import eu.mihosoft.vmf.core.*;\n"+
            "@InterfaceOnly\n"+
            "interface MyProperty {\n"+
            "    @GetterOnly String getName();\n"+
            "}"
            );
            addModelCode("getteronlypropvalid.vmfmodel.ImmutableObj",
            "package getteronlypropvalid.vmfmodel;\n"+
            "import eu.mihosoft.vmf.core.*;\n"+
            "@Immutable\n"+
            "interface ImmutableObj {\n"+
            "    MyProperty getProperty();\n"+
            "}"
            );
            setupModelFromCode();
            Assert.fail("Should throw an exception!");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testImmutabilityInalidIndirectInheritedMutableProperty() throws Throwable {
        try {
            addModelCode("getteronlypropvalid.vmfmodel.MyProperty",
            "package getteronlypropvalid.vmfmodel;\n" + 
            "import eu.mihosoft.vmf.core.*;\n"+
            "@InterfaceOnly\n"+
            "interface MyMutableProperty {\n"+
            "    String getName();\n"+
            "}\n"+
            // ---
            "import eu.mihosoft.vmf.core.*;\n"+
            "@InterfaceOnly\n"+
            "interface MyProperty extends MyMutableProperty{\n"+
            "    @GetterOnly String getName();\n"+
            "}\n"+
            // ---
            "@Immutable\n"+
            "interface ImmutableObj {\n"+
            "    MyProperty getProperty();\n"+
            "}"
            );
            setupModelFromCode();
            Assert.fail("Should throw an exception!");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testImmutabilityInalidIndirectMutableProperty() throws Throwable {
        try {

            addModelCode("getteronlypropinvalid.vmfmodel.ImmutableObj",
            "package getteronlypropinvalid.vmfmodel;\n"+
            "@InterfaceOnly\n"+
            "interface MyMutableProperty {\n"+
            "    String getName();\n"+
            "}\n"+
            // ---
            "@InterfaceOnly\n"+
            "interface MyProperty {\n"+
            "    @GetterOnly MyMutableProperty getName();\n"+
            "}"+
            // ---
            "import eu.mihosoft.vmf.core.*;\n"+
            "@Immutable\n"+
            "interface ImmutableObj {\n"+
            "    MyProperty getProperty();\n"+
            "}"
            );
            setupModelFromCode();
            Assert.fail("Should throw an exception!");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // already covered in test src test/vmf/getteronly
    // @Test
    // public void testGetterOnlyInterfaceOnlyAsCommonInterface() throws Throwable {
    //     try {
    //         addModelCode("commoniface.vmfmodel.WithName",
    //         "package commoniface.vmfmodel;\n" + 
    //         "import eu.mihosoft.vmf.core.*;\n"+
    //         "@InterfaceOnly\n"+
    //         "interface WithName {\n"+
    //         "    @GetterOnly String getName();\n"+
    //         "}"
    //         );
    //         addModelCode("commoniface.vmfmodel.ImmutableObj",
    //         "package commoniface.vmfmodel;\n" + 
    //         "import eu.mihosoft.vmf.core.*;\n"+
    //         "@Immutable\n"+
    //         "interface ImmutableObj extends WithName { }\n"
    //         );
    //         addModelCode("commoniface.vmfmodel.MutableObj",
    //         "package commoniface.vmfmodel;\n" + 
    //         "import eu.mihosoft.vmf.core.*;\n"+
    //         "interface MutableObj extends WithName { }\n"
    //         );
            
    //         setupModelFromCode();
            
    //     } catch (Exception ex) {
    //         Assert.fail("Should not throw an exception! " + ex.getClass() + ": " + ex.getMessage());
    //         ex.printStackTrace();
    //     }
    // }

    @Test
    public void testGetterOnlyInterfaceOnlyWithModifiableProperties() throws Throwable {
        try {

            addModelCode("commoniface.vmfmodel.NormalProperty",
            "package commoniface.vmfmodel;\n"+
            "import eu.mihosoft.vmf.core.*;\n"+
            "public interface NormalProperty { String getName(); }\n"+
            "@InterfaceOnly interface InterfaceOnlyGetterOnlyType {\n"+
            "    @GetterOnly NormalProperty getParent();\n"+
            "}"
            );
            
            setupModelFromCode();
            
        } catch (Exception ex) {
            Assert.fail("Should not throw an exception! " + ex.getClass() + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

}
