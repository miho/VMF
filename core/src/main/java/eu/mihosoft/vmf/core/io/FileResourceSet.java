/*
 * Copyright 2017-2023 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2023 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
package eu.mihosoft.vmf.core.io;

import java.io.File;

/**
 * A file resource set used for Java code generation.
 * @author Sam
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public final class FileResourceSet implements ResourceSet {

    //
    // thanks to Sam for designing this interface
    //

    /**
     * root folder
     */
    private final File rootSrcFolder;

    /**
     * Creates a new file resource set.
     * 
     * @param rootSrcFolder root folder of this resource set
     */
    public FileResourceSet(File rootSrcFolder) {
        if (rootSrcFolder.exists() && !rootSrcFolder.isDirectory()) {
            throw new IllegalArgumentException("Root src path not a directory.");
        }
        this.rootSrcFolder = rootSrcFolder;
        this.rootSrcFolder.mkdirs();
    }

    @Override
    public Resource open(String url) {
        return new FileResource(new File(rootSrcFolder, url));
    }


    /**
     * Returns the root folder of this resource set.
     * @return root folder of this resource set
     */
    public File getRootSrcFolder() {
        return rootSrcFolder;
    }
}
