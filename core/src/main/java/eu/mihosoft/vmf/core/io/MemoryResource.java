/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.CharArrayReader;
import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Memory resource. This class can be used for writing to memory instead of a
 * file.
 *
 * @see FileResource
 * @author Sam
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public class MemoryResource implements Resource {
    private ByteArrayOutputStream outputStream;
    private CharArrayWriter mem = new CharArrayWriter();

    @Override
    @Deprecated
    public PrintWriter open() throws IOException {
        return new PrintWriter(mem);
    }

    @Override
    public void close() throws IOException {

        if(this.outputStream!=null) {
            mem.append(new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
        }

        this.outputStream = null;
    }

    public String asString() {
        return mem.toString();
    }

    @Override
    public InputStream openForReading() {

        if(this.outputStream!=null) {
            mem.append(new String(outputStream.toByteArray(), StandardCharsets.UTF_8));
        }

        return new ByteArrayInputStream(mem.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public OutputStream openForWriting() {
        this.outputStream = new ByteArrayOutputStream();
        return this.outputStream;
    }

    int size() {
        return mem.size();
    }
}
