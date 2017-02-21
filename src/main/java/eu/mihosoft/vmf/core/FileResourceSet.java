package eu.mihosoft.vmf.core;

import java.io.File;

public final class FileResourceSet implements ResourceSet {

    //
    // thanks to Sam for designing this interface
    //
    final File rootSrcFolder;

    public FileResourceSet(File rootSrcFolder) {
        if (rootSrcFolder.exists() && !rootSrcFolder.isDirectory()) {
            throw new IllegalArgumentException("Root src path not a directory.");
        }
        this.rootSrcFolder = rootSrcFolder;
        this.rootSrcFolder.mkdirs();
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
        if (!folder.exists() && !folder.mkdirs()) {
            throw new RuntimeException("Failed to create folder "
                    + folder.getPath() + " for class " + fqn);
        }
        File file = new File(folder, javaFile);
        return file;
    }

}
