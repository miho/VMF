package eu.mihosoft.vmf.targets.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

interface Library {
    String getName();

    @Contains(opposite = "library")
    Book[] getBooks();
}

interface Book {
    String getTitle();

    @Container(opposite = "books")
    Library getLibrary();
}
