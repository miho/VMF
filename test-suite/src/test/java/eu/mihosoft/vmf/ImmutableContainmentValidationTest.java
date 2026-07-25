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
package eu.mihosoft.vmf;

import eu.mihosoft.vmf.testing.VMFTestShell;
import org.junit.Assert;
import org.junit.Test;

/**
 * Regression test for https://github.com/miho/VMF/issues/70.
 *
 * <p>When a mutable type declares a containment property whose (referenced) type is immutable,
 * generation must fail with a message that names the <em>immutable</em> type as the subject and
 * uses the correct container/contained wording. Previously the message wrongly labelled the
 * mutable owner as immutable and swapped "container of" / "contained in".</p>
 */
public class ImmutableContainmentValidationTest extends VMFTestShell {

    private static final String PKG = "eu.mihosoft.vmftests.immutablecontainment.vmfmodel";

    /**
     * A mutable {@code Parent} that contains an immutable {@code Child} via a single-valued
     * {@code @Contains} property. Only the immutable-as-contained case is exercised here; the
     * immutable {@code Child} itself has no containment property, so exactly one error is produced.
     */
    @Test
    public void immutableContainedTypeIsReportedAsContained() {
        addModelCode(PKG + ".Parent",
                "package " + PKG + ";\n" +
                "import eu.mihosoft.vmf.core.*;\n" +
                "public interface Parent {\n" +
                "    @Contains\n" +
                "    Child getChild();\n" +
                "}\n");
        addModelCode(PKG + ".Child",
                "package " + PKG + ";\n" +
                "import eu.mihosoft.vmf.core.*;\n" +
                "@Immutable\n" +
                "public interface Child {\n" +
                "    String getName();\n" +
                "}\n");

        Throwable ex = null;
        try {
            setupModelFromCode();
        } catch (Throwable t) {
            ex = t;
        }

        Assert.assertNotNull("generation must fail: an immutable type cannot take part in containment", ex);

        String msg = allMessages(ex);

        // the immutable type (Child) must be the subject, not the mutable owner (Parent)
        Assert.assertTrue("message must name the immutable Child type as subject: " + msg,
                msg.contains("Immutable type '") && msg.contains(".Child'"));
        // and it must use the correct 'contained in' wording ...
        Assert.assertTrue("message must say 'cannot be contained in type': " + msg,
                msg.contains("cannot be contained in type"));
        // ... not the swapped 'container of' wording (the actual bug in #70)
        Assert.assertFalse("message must not use the swapped 'container of' wording: " + msg,
                msg.contains("cannot be container of type"));
    }

    private static String allMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }
}
