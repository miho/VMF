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
		List<String> compileSourceRoots = mojo.getProject().getTestCompileSourceRoots();
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
