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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.tools.ToolProvider;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Instances of this class are used to test the VMF maven plugin.
 */
public class TestVMFMojo {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();


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

	@Test
	public void generatesCSharpAndTypeScriptBesidesJava() throws Exception {
		GenerateVMFSourcesMojo mojo = new GenerateVMFSourcesMojo();
		mojo.setSourceDirectory(new File("src/test/resources/test/vmf"));
		File generated = temporaryFolder.newFolder("generated-sources");
		mojo.setTargetDirectory(new File(generated, "java-vmf"));
		mojo.setTargets(Arrays.asList("csharp", "TypeScript", "java"));
		mojo.setProject(new MavenProject());
		mojo.execute();
		assertTrue(new File(generated, "csharp-vmf/Eu/Mihosoft/Model.g.cs").isFile());
		assertTrue(new File(generated, "typescript-vmf/eu/mihosoft/model.g.ts").isFile());
		assertEquals(1, mojo.getProject().getCompileSourceRoots().size());
	}

	@Test(expected = MojoExecutionException.class)
	public void unknownTargetsFail() throws Exception {
		GenerateVMFSourcesMojo mojo = new GenerateVMFSourcesMojo();
		mojo.setSourceDirectory(new File("src/test/resources/test/vmf"));
		mojo.setTargetDirectory(temporaryFolder.newFolder("java-vmf"));
		mojo.setTargets(Collections.singletonList("kotlin"));
		mojo.setProject(new MavenProject());
		mojo.execute();
	}

	@Test
	public void mainModelUsesProjectCompileClasspath() throws Exception {
		File classes = compileExternalType("compilefixture", "MainValue");
		GenerateVMFSourcesMojo mojo = new GenerateVMFSourcesMojo();
		mojo.setProject(new MavenProject() {
			@Override
			public List<String> getCompileClasspathElements() {
				return Collections.singletonList(classes.getAbsolutePath());
			}
		});

		String generated = generateModel(mojo, "import compilefixture.MainValue;\n",
				"MainValue getMainValue();");
		assertTrue(generated.contains("compilefixture.MainValue getMainValue()"));
	}

	@Test
	public void testModelUsesProjectTestClasspath() throws Exception {
		File mainClasses = compileExternalType("compilefixture", "MainValue");
		File testClasses = compileExternalType("testfixture", "TestValue");
		GenerateVMFTestSourcesMojo mojo = new GenerateVMFTestSourcesMojo();
		mojo.setProject(new MavenProject() {
			@Override
			public List<String> getTestClasspathElements() {
				return Arrays.asList(mainClasses.getAbsolutePath(), testClasses.getAbsolutePath());
			}
		});

		String generated = generateModel(mojo,
				"import compilefixture.MainValue;\nimport testfixture.TestValue;\n",
				"MainValue getMainValue();\nTestValue getTestValue();");
		assertTrue(generated.contains("compilefixture.MainValue getMainValue()"));
		assertTrue(generated.contains("testfixture.TestValue getTestValue()"));
	}

	private File compileExternalType(String packageName, String typeName) throws IOException {
		File source = temporaryFolder.newFile(typeName + ".java");
		File classes = temporaryFolder.newFolder(typeName + " classes % #");
		String code = "package " + packageName + ";\npublic final class " + typeName + " {}";
		Files.write(source.toPath(), code.getBytes(StandardCharsets.UTF_8));
		int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
				"-d", classes.getAbsolutePath(), source.getAbsolutePath());
		assertEquals("Could not compile the external fixture", 0, result);
		return classes;
	}

	private String generateModel(AbstractVMFMojo mojo, String imports, String properties) throws Exception {
		File sourceRoot = temporaryFolder.newFolder("models");
		File modelDir = new File(sourceRoot, "model/vmfmodel");
		Files.createDirectories(modelDir.toPath());
		File source = new File(modelDir, "Example.java");
		String code = "package model.vmfmodel;\n" + imports + "public interface Example {\n" + properties + "\n}";
		Files.write(source.toPath(), code.getBytes(StandardCharsets.UTF_8));
		File target = temporaryFolder.newFolder("generated");
		mojo.setSourceDirectory(sourceRoot);
		mojo.setTargetDirectory(target);
		mojo.execute();
		byte[] sourceBytes = Files.readAllBytes(new File(target, "model/Example.java").toPath());
		return new String(sourceBytes, StandardCharsets.UTF_8);
	}
}
