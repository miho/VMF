package eu.mihosoft.vmf.maven;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import eu.mihosoft.vmf.VMF;

/**
 * This abstract class is the base for the VMFMojos used to generate sources during Maven build.
 */
public abstract class AbstractVMFMojo extends AbstractMojo {
	/** The maven project. */
	@Parameter(defaultValue = "${project}", required = true, readonly = true) protected MavenProject project;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		Log log = getLog();
		File sourceDirectory = getSourceDirectory();
		// do nothing in case of missing source directory
		if (!sourceDirectory.exists()) {
			log.info("Source folder ignored as it does not exist:"
				+ sourceDirectory);
			return;
		}
		File[] sourceFiles = getSourceFiles(sourceDirectory);
		// do nothing if no source files found
		if (sourceFiles.length == 0) {
			log.info("Nothing to do, no source files found in "
				+ sourceDirectory);
			return;
		}
		File targetDirectory = getTargetDirectory();
		// create non-existant target directory
		if (!targetDirectory.exists()) {
			log.info("Creating VMF ouput directory for generated Java classes: "
				+ targetDirectory);
			boolean success = targetDirectory.mkdirs();
			if (!success) {
				log.error("VMF generation ignored, could not create vmf destination directory: "
					+ targetDirectory);
				return;
			}
		}
		// log some infos for user
		log.info("Generating Java classes from vmf model: "
			+ sourceDirectory
			+ " => "
			+ targetDirectory);
		addToSources(targetDirectory.getAbsolutePath());
		// generate VMF sources
		try {
			VMF.generate(targetDirectory, sourceFiles);
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
	 * @param fileList file list where source files are added to.
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
	 * @param sourcePath the target directory with Java source files to the compile
	 *                   path.
	 */
	protected abstract void addToSources(String sourcePath);

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
