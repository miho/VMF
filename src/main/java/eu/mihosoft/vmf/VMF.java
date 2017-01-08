package eu.mihosoft.vmf;

import eu.mihosoft.vmf.core.FileResourceSet;
import eu.mihosoft.vmf.core.CodeGenerator;
import eu.mihosoft.vmf.tutorial.vmfmodel.ChildNode;
import eu.mihosoft.vmf.tutorial.vmfmodel.ContainerNode;
import eu.mihosoft.vmf.tutorial.vmfmodel.SimpleNode;


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

        generator.generate(new FileResourceSet(new File("C:\\Users\\miho\\Documents\\tmp\\VMFTest\\src\\main\\java")), SimpleNode.class, ContainerNode.class, ChildNode.class);


        writer.close();

        System.out.println(writer.toString());
    }
}
