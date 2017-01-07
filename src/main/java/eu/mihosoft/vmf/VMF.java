package eu.mihosoft.vmf;

import eu.mihosoft.vmf.core.CodeGenerator;
import eu.mihosoft.vmf.core.FileResourceSet;
import eu.mihosoft.vmf.core.Model;


import java.io.File;
import java.io.StringWriter;

/**
 * Created by miho on 03.01.2017.
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class VMF {
    public static void main(String[] args) throws Exception {
        CodeGenerator generator = new CodeGenerator();



//        Model model = new Model();
//        model.init(SimpleNode.class);

        StringWriter writer = new StringWriter();

        generator.generate(new FileResourceSet(new File("src-gen")), SimpleNode.class);

//        generator.generateTypeInterface(
//                writer,
//                model.getTypes().iterator().next());

        writer.close();

        System.out.println(writer.toString());
    }
}
