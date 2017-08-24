/*
 * Copyright 2016-2017 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmf.core;

import java.io.File;

/**
 * 
 * @author Sam
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
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
