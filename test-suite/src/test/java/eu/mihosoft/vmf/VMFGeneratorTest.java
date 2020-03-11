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