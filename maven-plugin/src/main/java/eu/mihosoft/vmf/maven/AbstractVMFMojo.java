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
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import eu.mihosoft.vmf.VMF;

/**
 * This abstract class is the base for the VMFMojos used to generate sources
 * during Maven build.
 * 
 * There are two sub classes of this class
 * <ul>
 *   <li>GenerateVMFSourcesMojo - generates classes from models in project source tree</li>
 *   <li>GenerateVMFTestSourcesMojo - generates classes from models in project test tree</li>
 * </ul>
 * 
 * <p> The plugin execution is invoked with a
 * <ul>
 *   <li>source folder - the folder containing the model files (Java source files)</li>
 *   <li>target folder - the destination folder where generated code is written to</li>
 * </ul>
 * 
 * <p> The source folder with its sub directories is scanned for model files with extension .java.
 * By convention the parent directory of a model file must be named "vmfmodel".
 * All other model files are ignored and a warning is logged.
 * 
 * <p>After collecting all model files below the source directory they are grouped by their
 * parent directory.
 * 
 * <ul>
 *   <li>VMF/test-suite/src/test/vmf/eu/mihosoft/vmftest/complex/account/vmfmodel
 *     <ul>
 *       <li>Account.java</li>
 *     </ul>
 *   </li>
 *   <li>VMF/test-suite/src/test/vmf/eu/mihosoft/vmftest/annotations/vmfmodel
 *     <ul>
 *       <li>AnnotatedModel.java</li>
 *     </ul>
 *   </li>
 *   <li>VMF/test-suite/src/test/vmf/eu/mihosoft/vmftest/complex/unparsermodel/vmfmodel
 *     <ul>
 *       <li>GrammarLangModel.java</li>
 *       <li>UnparserModel.java</li>
 *     </ul>
 *   </il>
 * </ul>
 * 
 * <p>For every group code (model directory) code generation is executed.
 * 
 * <p>The generated target folder is added to the list of the Java source folders of the project afterwards.
 * 
 */
public abstract class AbstractVMFMojo extends AbstractMojo {
	/** The maven project. */
	@Parameter(defaultValue = "${project}", required = true, readonly = true)
	protected MavenProject project;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		Log log = getLog();
		// get source directory
		File sourceDirectory = getSourceDirectory();
		// do nothing in case of missing source directory
		if (!sourceDirectory.exists()) {
			log.info("Source folder ignored as it does not exist " + sourceDirectory);
			return;
		}
		// get all source files from source directory
		File[] sourceFiles = getSourceFiles(sourceDirectory);
		Map<File, List<File>> sourceModel = getSourceModel(sourceFiles);
		// do nothing if no source files found
		if (sourceModel.size() == 0) {
			log.info("Nothing to do, no source files found in " + sourceDirectory);
			return;
		}
		// get target directory
		File targetDirectory = getTargetDirectory();
		// create non-existant target directory
		if (!targetDirectory.exists()) {
			log.info("Creating VMF ouput directory for generated Java classes " + targetDirectory);
			boolean success = targetDirectory.mkdirs();
			if (!success) {
				log.error("VMF generation ignored, could not create vmf destination directory " + targetDirectory);
				return;
			}
		}
		// start generation
		log.info("Generating source files from " + sourceFiles.length + " model file" + ((sourceFiles.length == 1) ? "" : "s") + " to "
				+ targetDirectory);
		// add target folder to project sources
		addGeneratedFolderToProject(getTargetDirectory().getAbsolutePath());
		// generate VMF sources
		// iterate over all packages an run generation process
		File[] packageFiles = sourceModel.keySet().toArray(new File[sourceModel.size()]);
		try {
			for (File packageFile : packageFiles) {
				// run generation process fpr package
				List<File> sourceFileList = sourceModel.get(packageFile);
				File[] sourceFileArray = sourceFileList.toArray(new File[sourceFileList.size()]);
				// log some infos for user
				log.info("Generating Java classes from vmf model for package in " + packageFile.getParent());
				VMF.generate(targetDirectory, sourceFileArray);
			}
		} catch (IOException e) {
			StringBuffer sb = new StringBuffer();
			sb.append(e.getMessage());
			sb.append(", targetDirectory=");
			sb.append(targetDirectory);
			sb.append(", sourcefiles=");
			for (File file : sourceFiles) {
				sb.append(file);
				sb.append(", ");
			}
			log.error(e);
			throw new MojoExecutionException(sb.toString(), e);
		}
	}

	/**
	 * Converts the list of source files to a model map.
	 * <p>For every model directory all contained model sources
	 * are collected and stored with the model directory as key.
	 * @param sourceFiles the complete list of all source file.
	 * @return map with model directory and corresponding model sources.
	 */
	private Map<File, List<File>> getSourceModel(File[] sourceFiles) {
		Map<File, List<File>> packageMap = new HashMap<File, List<File>>();
		for (File sourceFile : sourceFiles) {
			File file = sourceFile.getAbsoluteFile();
			File parentFile = file.getParentFile();
			if (!parentFile.getName().equals("vmfmodel")) {
				Log log = getLog();
				log.info("Warning, source files in directory " + parentFile.getParentFile().getPath()
						+ " ignored (residing in wrong parent directory " + parentFile.getName()
						+ ", expected vmfmodel)");
				continue;
			}
			List<File> fileList = packageMap.get(parentFile);
			if (fileList == null) {
				fileList = new ArrayList<File>();
				packageMap.put(parentFile, fileList);
			}
			fileList.add(sourceFile);
		}
		return packageMap;
	}

	/**
	 * Gets all source files from a source directory.
	 * 
	 * @param directory the source directory.
	 * @return the source files.
	 */
	private File[] getSourceFiles(File directory) {
		List<File> fileList = new ArrayList<>();
		getSourceFiles(directory, fileList);
		return fileList.toArray(new File[fileList.size()]);
	}

	/**
	 * Gets all source files from a source directory.
	 * 
	 * @param directory the source directory.
	 * @param fileList  file list where source files are added to.
	 */
	private void getSourceFiles(File directory, List<File> fileList) {
		File[] sourceFiles = directory.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.isFile() && file.getName().endsWith(".java");
			}
		});
		fileList.addAll(Arrays.asList(sourceFiles));
		File[] subDirs = directory.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.isDirectory();
			}
		});
		for (File subDir : subDirs) {
			getSourceFiles(subDir, fileList);
		}
	}

	/**
	 * Gets the source directory with the VMF model files.
	 * 
	 * @return the source directory with the VMF model files.
	 */
	protected abstract File getSourceDirectory();

	/**
	 * Sets the source directory with the VMF model files.
	 * 
	 * @param sourceDirectory the source directory with the VMF model files.
	 */
	protected abstract void setSourceDirectory(File sourceDirectory);

	/**
	 * Gets the target directory for the generated Java files.
	 * 
	 * @return the target directory for the generated Java files.
	 */
	protected abstract File getTargetDirectory();

	/**
	 * Sets the target directory for the generated Java files.
	 * 
	 * @return the target directory for the generated Java files.
	 */
	protected abstract void setTargetDirectory(File targetDirectory);

	/**
	 * Adds the target directory with Java source files to the compile path.
	 * 
	 * @param folder the generated folder path.
	 */
	protected abstract void addGeneratedFolderToProject(String folder);

	/**
	 * Gets the Maven project.
	 * 
	 * @return the Maven project.
	 */
	protected MavenProject getProject() {
		return project;
	}

	/**
	 * sets the Maven project.
	 * 
	 * @param project the Maven project.
	 */
	public void setProject(MavenProject project) {
		this.project = project;
	}
}
