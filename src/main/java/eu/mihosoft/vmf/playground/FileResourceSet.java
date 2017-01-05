package eu.mihosoft.vmf.playground;

import java.io.File;

public class FileResourceSet implements ResourceSet {

	final File rootSrcFolder;

	public FileResourceSet(File rootSrcFolder) {
		if (!rootSrcFolder.isDirectory())
			throw new IllegalArgumentException("Root src path not a directory.");
		this.rootSrcFolder = rootSrcFolder;
	}

	@Override
	public Resource open(String url) {
		File file = computeFileNameFromJavaFQN(url);
		return new FileResource(file);
	}

	public File computeFileNameFromJavaFQN(String fqn) {
		String path = fqn.substring(0, fqn.lastIndexOf('.')).replace('.',
				File.separatorChar);
		String javaFile = fqn.substring(fqn.lastIndexOf('.') + 1) + ".java";
		File folder = new File(rootSrcFolder, path);
		if (!folder.exists() && !folder.mkdirs())
			throw new RuntimeException("Failed to create folder "
					+ folder.getPath() + " for class " + fqn);
		File file = new File(folder, javaFile);
		return file;
	}

}
