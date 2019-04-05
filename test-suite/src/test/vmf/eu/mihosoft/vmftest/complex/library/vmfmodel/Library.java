package eu.mihosoft.vmftest.complex.library.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Refers;

interface Library {
    String getName();
    @Contains(opposite="library")
    Book[] getBooks();
    
    @Contains(opposite="library")
    Writer[] getAuthors();
}

interface Book {

    String getTitle();
    
    Integer getPages();

    @Container(opposite="books")
    Library getLibrary(); 

    @Refers(opposite="books")
    Writer[] getAuthors();
}

interface Writer {
    String getName();

    @Container(opposite="authors")
    Library getLibrary();

    @Refers(opposite="authors")
    Book[] getBooks();
}