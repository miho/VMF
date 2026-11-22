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
package eu.mihosoft.vmf.jackson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** The document formats shared by VMF's journal and feature codecs. */
public enum VMFDocumentFormat {
    JSON("json"),
    YAML("yaml", "yml"),
    XML("xml");

    private final List<String> extensions;

    VMFDocumentFormat(String... extensions) {
        this.extensions = List.of(extensions);
    }

    /**
     * Selects the format from the filename extension. Extensionless filenames use JSON.
     * @param path path to a VMF document
     * @return the selected format
     * @throws IOException if the path has no filename or its extension is unsupported
     */
    public static VMFDocumentFormat fromPath(Path path) throws IOException {
        Path filename = path.getFileName();
        if (filename == null || filename.toString().isEmpty()) {
            throw new IOException("Expected a VMF document filename: " + path);
        }
        String name = filename.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return JSON;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (VMFDocumentFormat format : values()) {
            if (format.extensions.contains(extension)) {
                return format;
            }
        }
        throw new IOException("Unsupported VMF document extension in '" + name
                + "'. Select a VMFDocumentFormat explicitly.");
    }
}
