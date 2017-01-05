package eu.mihosoft.vmf;

import eu.mihosoft.vmf.playground.Model;
import eu.mihosoft.vmf.playground.ModelGenerator;

import java.io.StringWriter;

/**
 * Created by miho on 03.01.2017.
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class VMF {
    public static void main(String[] args) throws Exception {
        ModelGenerator generator = new ModelGenerator();



        Model model = new Model();
        model.init(SimpleNode.class);

        StringWriter writer = new StringWriter();

        generator.generateTypeInterface(
                writer,
                model.getTypes().iterator().next());

        writer.close();

        System.out.println(writer.toString());
    }
}
