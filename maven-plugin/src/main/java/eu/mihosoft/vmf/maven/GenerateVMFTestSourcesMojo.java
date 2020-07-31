package eu.mihosoft.vmf.maven;

import java.io.File;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Generates Java implementation classes from test source files in VMF model
 * definition during Maven build.
 * <p>
 * Parameters:
 * <ul>
 * <li>vmf.testSourceDirectory - the vmf model test source directory (default:
 * ${basedir}/src/test/vmf)</li>
 * <li>vmf.testTargetDirectory - the directory with generated Java test files (default:
 * ${project.build.directory}/generated-sources/test-vmf)</li>
 * </ul>
 * </p>
 * 
 */
@Mojo(name = "vmf-test", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES, threadSafe = true)
public class GenerateVMFTestSourcesMojo extends AbstractVMFMojo {
	/** The source directory containing the vmf test model files. */
	@Parameter(property = "vmf.testSourceDirectory", defaultValue = "${basedir}/src/test/vmf")
	private File sourceDirectory;

	/** The target folder where the generated Java test classes will be saved to. */
	@Parameter(property = "vmf.testTargetDirectory", defaultValue = "${project.build.directory}/generated-test-sources/java-vmf")
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
	protected void setTargetDirectory(File targetDirectory) {
		this.targetDirectory = targetDirectory;
	}

	@Override
	protected File getTargetDirectory() {
		return targetDirectory;
	}

	@Override
	protected void addToSources(String sourcePath) {
		getProject().addTestCompileSourceRoot(sourcePath);
	}
}
