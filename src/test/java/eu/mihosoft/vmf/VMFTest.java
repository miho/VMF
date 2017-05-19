package eu.mihosoft.vmf;

import eu.mihosoft.vmf.testmodels.invalidclassesmodel.vmfmodel.ModelInvalidClasses;
import eu.mihosoft.vmf.testmodels.vmfmodel.ModelInValidPackage;
import eu.mihosoft.vmf.testmodels.invalid.ModelInInvalidPackage;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
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

        Assert.assertTrue("VMF code generator must throw exception if empty models are specified.",
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

        Assert.assertTrue("VMF code generator must throw exception if empty models are specified.",
                emptyModelException);

        try {
            VMF.generate(tmpDir.toFile(),
                    "vmftestingpkg.vmfmodel");
        } catch (IllegalArgumentException ex) {
            emptyModelException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw exception if empty models are specified.",
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

        Assert.assertTrue("VMF code generator must throw exception if model package is invalid.",
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

        Assert.assertTrue("VMF code generator must throw exception if model package is invalid.",
                invalidModelPackageException);

        try {
            VMF.generate(tmpDir.toFile(),
                    "eu.mihosoft.vmf.testmodels.invalid");
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw exception if model package is invalid.",
                invalidModelPackageException);

    }

    @Test
    public void vmfValidModelPackageTest() throws IOException {

        boolean invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(), ModelInValidPackage.class);
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertFalse("VMF code generator must not throw exception if model package is valid.",
                invalidModelPackageException);

        invalidModelPackageException = false;

        try {
            VMF.generate(tmpDir.toFile(),
                    getClass().getClassLoader(),
                    "eu.mihosoft.vmf.testmodels.vmfmodel");
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertFalse("VMF code generator must not throw exception if model package is valid.",
                invalidModelPackageException);

        try {
            VMF.generate(tmpDir.toFile(),
                    "eu.mihosoft.vmf.testmodels.vmfmodel");
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertFalse("VMF code generator must not throw exception if model package is valid.",
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

        Assert.assertTrue("VMF code generator must throw exception if model package contains classes.",
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

        Assert.assertTrue("VMF code generator must throw exception if model package contains classes.",
                invalidModelPackageException);

        try {
            VMF.generate(tmpDir.toFile(),
                    "eu.mihosoft.vmf.testmodels.invalidclassesmodel.vmfmodel");
        } catch (IllegalArgumentException ex) {
            invalidModelPackageException = true;
            ex.printStackTrace(System.err);
        }

        Assert.assertTrue("VMF code generator must throw exception if model package contains classes.",
                invalidModelPackageException);
    }


}
