package service.security;

import org.junit.jupiter.api.Test;
import service.util.Mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MaskTest {

    @Test void phoneShowsLastFour() {
        assertEquals("*******4567", Mask.phone("05551234567"));
    }

    @Test void phoneNullStaysNull() { assertNull(Mask.phone(null)); }

    @Test void phoneShortKeepsAll() {
        assertEquals("***12", Mask.phone("12"));
    }

    @Test void emailMasksLocalPart() {
        assertEquals("o****@gmail.com", Mask.email("ozelmail@gmail.com"));
    }

    @Test void emailWithoutAt() {
        assertEquals("***", Mask.email("notanemail"));
    }

    @Test void cardShowsLastFour() {
        assertEquals("****1234", Mask.card("4111 1111 1111 1234"));
    }

    @Test void tokenAlwaysMasked() {
        assertEquals("<masked>", Mask.token("secret-token-abc123"));
    }

    @Test void userKeepsFirstAndLast() {
        String m = Mask.user("hatice");
        assertEquals("h***e", m);
        assertNotEquals("hatice", m);
    }

    @Test void userTooShort() {
        assertNotNull(Mask.user("ab"));
        assertTrue(Mask.user("ab").contains("*"));
    }
}
