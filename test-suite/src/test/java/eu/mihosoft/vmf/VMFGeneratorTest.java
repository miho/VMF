/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
package eu.mihosoft.vmf;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import eu.mihosoft.jcompiler.CompilationException;
import eu.mihosoft.jcompiler.JCompiler;
import eu.mihosoft.vmf.core.io.MemoryResource;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import eu.mihosoft.vmf.core.io.ResourceSet;

public class VMFGeneratorTest {

    @Test
    public void vmfCompileAndGenerate() throws IOException, ClassNotFoundException, CompilationException {

        MemoryResourceSet targetFolder = new MemoryResourceSet();

        MemoryResource resource = new MemoryResource();
        OutputStream outputStream = resource.openForWriting();
        
        new PrintWriter(outputStream,true).println(
            "package a.test.vmfmodel;\n"+
            "import eu.mihosoft.vmf.core.*;\n"+
            "interface Parent { String getName(); @Contains(opposite=\"parent\") Child getChild(); }\n"+
            "interface Child { String getName(); @Container(opposite=\"child\") Parent getParent(); }");

        VMF.generate(targetFolder, resource);
        JCompiler compiler = JCompiler.newInstance();
        for (Map.Entry<String, MemoryResource> entry : targetFolder.getMemSet().entrySet()) {
            String code = entry.getValue().asString();

            Assert.assertFalse("Code entry must not be empty: " + entry.getKey(), code.isEmpty());

            compiler.addSource(code);
        }

        // we test whether compilation is successful and does not throw an exception
        compiler.compileAll().checkNoErrors(true).loadClasses().values().forEach(cc-> {
            System.out.println("cls: " + cc.getName());
        });
    }
}