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