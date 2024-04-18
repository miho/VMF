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
package eu.mihosoft.vmftest.complex.library;

import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class LibraryTest {

    @Test
    public void createLibraryTest() {
        Library library = Library.newInstance();
        library.setName("My Library");

        Book b1 = Book.newBuilder().withTitle("Mastering VMF").withPages(350).build();
        Writer w1 = Writer.newBuilder().withName("The Author").withBooks(b1).build();

        library.getAuthors().add(w1);
        library.getBooks().add(b1);

        assertThat("Library must contain a book", library.getBooks(), contains(b1));
        assertThat("Library must contain an author", library.getAuthors(), contains(w1));
        
        assertThat("The book must reference its author", b1.getAuthors(), contains(w1));
        assertThat("The author must reference his/her books", w1.getBooks(), contains(b1));
        
        
    }
}