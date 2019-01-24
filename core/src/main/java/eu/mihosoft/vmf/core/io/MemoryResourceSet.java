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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Hashtable;

public class MemoryResourceSet implements ResourceSet {

    private Hashtable<String, MemoryResource> memSet = new Hashtable<>();

    @Override
    public Resource open(String url) {
        if (memSet.containsKey(url)) return memSet.get(url);
        else {
            MemoryResource res = new MemoryResource();
            memSet.put(url, res);
            return res;
        }
    }

    public Hashtable<String, MemoryResource> getMemSet() {
        return memSet;
    }

    public String asString() {
        StringWriter out = new StringWriter();
        for (String url : memSet.keySet()) {
            out.write("\n");
            out.write("--------------------------------------------------------------------------------\n");
            out.write("ENTRY " + url+"\n");
            out.write("--------------------------------------------------------------------------------\n");
            out.write(memSet.get(url).asString());
        }
        return out.toString();
    }

    public void printStats(PrintWriter out) {
        out.println("Resource count: " + memSet.keySet().size());
        out.println("Resource urls: ");
        for (String url : memSet.keySet()) {
            out.println("- " + url + " (" + memSet.get(url).mem.size() + ")");
        }
    }
}
