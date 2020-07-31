package eu.mihosoft.vmf.maven;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.Test;

/**
 * Instances of this class are used to test the VMF maven plugin.
 */
public class TestVMFMojo {

	/**
	 * Tests the GenerateVMFSourcesMojo.
	 * @throws MojoExecutionException if an unexpected problem occurs. Throwing this exception causes a "BUILD ERROR" message to be displayed.
	 * @throws MojoFailureException if an expected problem (such as a compilation failure) occurs. Throwing this exception causes a "BUILD FAILURE" message to be displayed.
	 */
	@Test
	public void testGenerateVMFSourcesMojo() throws MojoExecutionException, MojoFailureException {
		GenerateVMFSourcesMojo mojo = new GenerateVMFSourcesMojo();
		File sourceDirectory = new File("src/test/resources/test/vmf");
		mojo.setSourceDirectory(sourceDirectory);
		File targetDirectory = new File("target/test/generated-sources/java-vmf");
		mojo.setTargetDirectory(targetDirectory);
		mojo.setProject(new MavenProject());
		mojo.execute();
		List<String> compileSourceRoots = mojo.getProject().getCompileSourceRoots();
		assertEquals("Unexpected number of source roots encountered", 1, compileSourceRoots.size());
		assertEquals("Wrong source directory found", compileSourceRoots.get(0), targetDirectory.getAbsolutePath());
	}

	/**
	 * Tests the GenerateVMFTestSourcesMojo.
	 * @throws MojoExecutionException if an unexpected problem occurs. Throwing this exception causes a "BUILD ERROR" message to be displayed.
	 * @throws MojoFailureException if an expected problem (such as a compilation failure) occurs. Throwing this exception causes a "BUILD FAILURE" message to be displayed.
	 */
	@Test
	public void testGenerateVMFTestSourcesMojo() throws MojoExecutionException, MojoFailureException {
		GenerateVMFTestSourcesMojo mojo = new GenerateVMFTestSourcesMojo();
		File sourceDirectory = new File("src/test/resources/test/vmf");
		mojo.setSourceDirectory(sourceDirectory);
		File targetDirectory = new File("target/test/generated-test-sources/java-vmf");
		mojo.setTargetDirectory(targetDirectory);
		mojo.setProject(new MavenProject());
		mojo.execute();
		List<String> compileSourceRoots = mojo.getProject().getTestCompileSourceRoots();
		assertEquals("Unexpected number of source roots encountered", 1, compileSourceRoots.size());
		assertEquals("Wrong source directory found", compileSourceRoots.get(0), targetDirectory.getAbsolutePath());
	}
}
