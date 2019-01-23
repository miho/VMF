/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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

import eu.mihosoft.vmf.testmodels.invalidclassesmodel.vmfmodel.ModelInvalidClasses;
import eu.mihosoft.vmf.testmodels.vmfmodel.ModelInValidPackage;
import eu.mihosoft.vmf.testmodels.invalid.ModelInInvalidPackage;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by miho on 19.05.17.
 */
public class VMFTest {

    private static Path tmpDir;

    @BeforeClass
    public static void initDirs() throws IOException {
        tmpDir = Files.createTempDirectory("vmf-testing-");
    }

    @Test
    public void vmfEmptyModelTest() throws IOException {

        boolean emptyModelException = false;

        try {
            VMF.generate(tmpDir.toFile());
        } catch (IllegalArgumentException ex) {
            emptyModelException = true;
        }

        Assert.assertTrue("VMF code generator must throw an exception if empty models are specified.",
                emptyModelException);

        emptyModelException = false;

        try {
            VMF.generate(tmpDir.toFile(),
                    getClass().getClassLoader(),
                    "vmftestingpkg.vmfmodel");
        } catch (IllegalArgumentException ex) {
            emptyModelException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw an exception if empty models are specified.",
                emptyModelException);

        try {
            VMF.generate(tmpDir.toFile(),
                    "vmftestingpkg.vmfmodel");
        } catch (IllegalArgumentException ex) {
            emptyModelException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw an exception if empty models are specified.",
                emptyModelException);

    }

    @Test
    public void vmfInvalidModelPackageTest() throws IOException {

        boolean invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(), ModelInInvalidPackage.class);
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw an exception if model package is invalid.",
                invalidModelPackageException);

        invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(),
                    getClass().getClassLoader(),
                    "eu.mihosoft.vmf.testmodels.invalid");
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw an exception if model package is invalid.",
                invalidModelPackageException);

        try {
            VMF.generate(tmpDir.toFile(),
                    "eu.mihosoft.vmf.testmodels.invalid");
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw an exception if model package is invalid.",
                invalidModelPackageException);

    }

    @Test
    public void vmfValidModelPackageTestFromClass() throws IOException {

        boolean invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(), ModelInValidPackage.class);
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertFalse("VMF code generator must not throw an exception if model package is valid.",
                invalidModelPackageException);

    }

    @Test
    public void vmfValidModelPackageTestFromClassLoader() throws IOException {

        boolean invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(),
                new URLClassLoader(new URL[]{new URL("file://./build/classes/java/test/")}),
                    "eu.mihosoft.vmf.testmodels.vmfmodel"
            );
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertFalse("VMF code generator must not throw an exception if model package is valid.",
                invalidModelPackageException);
    }

    @Test
    public void vmfValidModelPackageTestFromCurrentThreadClassLoader() throws IOException {

        boolean invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(),
                    "eu.mihosoft.vmf.testmodels.vmfmodel"
            );
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertFalse("VMF code generator must not throw an exception if model package is valid.",
                invalidModelPackageException);

    }

    @Test
    public void vmfClassesInPackageTest() throws IOException {

        boolean invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(), ModelInvalidClasses.class);
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw an exception if model package contains classes.",
                invalidModelPackageException);

        invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(),
                    getClass().getClassLoader(),
                    "eu.mihosoft.vmf.testmodels.invalidclassesmodel.vmfmodel");
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw an exception if model package contains classes.",
                invalidModelPackageException);

        try {
            VMF.generate(tmpDir.toFile(),
                    "eu.mihosoft.vmf.testmodels.invalidclassesmodel.vmfmodel");
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw an exception if model package contains classes.",
                invalidModelPackageException);
    }


}
