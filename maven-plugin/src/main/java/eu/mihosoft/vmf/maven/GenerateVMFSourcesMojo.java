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

import java.io.File;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Generates Java implementation classes from source files in VMF model
 * definition during Maven build.
 * <p>
 * Parameters:
 * <ul>
 * <li>vmf.sourceDirectory - the vmf model source directory (default:
 * ${basedir}/src/main/vmf)</li>
 * <li>vmf.targetDirectory - the directory with generated Java files (default:
 * ${project.build.directory}/generated-sources/java-vmf)</li>
 * </ul>
 * </p>
 * 
 */
@Mojo(name = "vmf", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE )
public class GenerateVMFSourcesMojo extends AbstractVMFMojo {
	/** Source directory containing the vmf model files. */
	@Parameter(property = "vmf.sourceDirectory", defaultValue = "${basedir}/src/main/vmf")
	private File sourceDirectory;

	/** The target folder where the generated Java classes will be saved to. */
	@Parameter(property = "vmf.targetDirectory", defaultValue = "${project.build.directory}/generated-sources/java-vmf")
	private File targetDirectory;

	@Override
	protected File getSourceDirectory() {
		return sourceDirectory;
	}

	@Override
	protected void setSourceDirectory(File sourceDirectory) {
		this.sourceDirectory = sourceDirectory;
	}

	@Override
	protected File getTargetDirectory() {
		return targetDirectory;
	}

	@Override
	protected void setTargetDirectory(File targetDirectory) {
		this.targetDirectory = targetDirectory;
	}

	@Override
	protected void addToSources(String sourcePath) {
		getProject().addCompileSourceRoot(sourcePath);
	}
}
