/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A file resource.
 * 
 * @see MemoryResource
 * 
 * @author Sam
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public class FileResource implements Resource {

    //
    // thanks to Sam for designing this interface
    //
    private final File file;
    private InputStream inputStream;
    private OutputStream outputStream;
    private PrintWriter writer;

    FileResource(File file) {
        this.file = file;
    }

    /**
     * Returns the file object associated with this resource set.
     * 
     * @return file object
     */
    public File getFile() {
        return this.file;
    }

    @Override
    public PrintWriter open() throws IOException {

        if (writer != null) {
            throw new RuntimeException("Resource already opened ('" + file.getAbsolutePath() + ")");
        }

        File folder = file.getParentFile();

        if (folder != null && !folder.exists() && !folder.mkdirs()) {
            throw new RuntimeException("Failed to create folder " + folder.getPath());
        }

        return this.writer = new PrintWriter(file, "UTF-8");
    }

    @Override
    public void close() throws IOException {
        if(this.writer!=null) this.writer.close();
        this.writer = null;
        IOException iox = null;
        if(this.inputStream!=null) {
            try {
                this.inputStream.close();
            } catch (IOException e) {
                iox = new IOException("Cannot close input stream",e);
            }
        }
        if(this.outputStream!=null) {
            try {
                this.outputStream.close();
            } catch (IOException e) {
            
                IOException iox2 = new IOException("Cannot close output stream", e);

                if(iox!=null) {
                    iox2.addSuppressed(iox);
                }

                iox = iox2;
            }
        }

        if(iox!=null) {
            throw iox;
        }
    }

    @Override
    public InputStream openForReading() throws IOException {
        if (writer != null || outputStream!=null) {
            throw new RuntimeException("Resource already opened for writing('" + file.getAbsolutePath() + ")");
        }

        if (inputStream != null) {
            throw new RuntimeException("Resource already opened for reading('" + file.getAbsolutePath() + ")");
        }

        this.inputStream = new FileInputStream(this.file);

        return this.inputStream;
    }

    @Override
    public OutputStream openForWriting() throws IOException {
        if (inputStream != null) {
            throw new RuntimeException("Resource already opened for reading('" + file.getAbsolutePath() + ")");
        }

        if (writer != null || outputStream!=null) {
            throw new RuntimeException("Resource already opened for writing('" + file.getAbsolutePath() + ")");
        }

        this.outputStream = new FileOutputStream(this.file);

        return this.outputStream;
    }

}
