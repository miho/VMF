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
