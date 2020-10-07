import java.io.File;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.mdkt.compiler.InMemoryJavaCompiler;

public class TestVMF {
	public static void main(String[] args) throws Exception {
		InMemoryJavaCompiler compiler = InMemoryJavaCompiler.newInstance();
		compiler.useParentClassLoader((URLClassLoader) TestVMF.class.getClassLoader());
		File sourceFile = new File("src/main/vmf/eu/mihosoft/vmfmodel/Parent.java");
		Class<?> result = compiler.compile("eu.mihosoft.vmfmodel.Parent", new String(Files.readAllBytes(sourceFile.toPath()), StandardCharsets.UTF_8));
		System.out.println(result);
		}
}
