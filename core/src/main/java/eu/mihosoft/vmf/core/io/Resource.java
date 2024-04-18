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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;


/**
 * A resource for storing code generated by VMF.
 * @author Sam
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
public interface Resource extends AutoCloseable {

    //
    // thanks to Sam for designing this interface
    //

    /**
     * Opens this resource for writing.
     * @return print writer for writing to this resource.
     * @throws IOException if an I/O related problem prevents this operation
     */
    PrintWriter open() throws IOException;

    /**
     * Opens this resource for reading.
     * @return input stream to read from this resource
     * @throws IOException if an I/O related problem prevents this operation
     */
    InputStream openForReading() throws IOException; 

    /**
     * Opens this resource for writing.
     * @return output stream to write from this resource
     * @throws IOException if an I/O related problem prevents this operation 
     */
    OutputStream openForWriting() throws IOException; 

    @Override
    void close() throws IOException;

}
