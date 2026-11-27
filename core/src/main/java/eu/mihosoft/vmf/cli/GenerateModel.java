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
package eu.mihosoft.vmf.cli;

import eu.mihosoft.vmf.GenerationTarget;
import eu.mihosoft.vmf.VMF;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Generates model sources from a set of Java VMF declarations. */
public final class GenerateModel {
    private GenerateModel() {
    }

    public static void main(String[] args) throws IOException {
        String targets = Arrays.stream(GenerationTarget.values())
                .map(target -> target.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining("|"));
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: GenerateModel <" + targets + "> <output-directory> <model.java>...");
        }

        GenerationTarget target;
        try {
            target = GenerationTarget.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown generation target '" + args[0]
                    + "'. Choose " + targets + ".", error);
        }

        File[] sources = Arrays.stream(args, 2, args.length).map(File::new).toArray(File[]::new);
        VMF.generate(target, new File(args[1]), sources);
    }
}
