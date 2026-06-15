package service.security;

import model.Role;
import model.User;
import org.junit.jupiter.api.Test;
import service.api.SessionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SessionStoreTest {

    private static User makeUser(String username) {
        // User no-arg ctor yok; (username, passwordHash, role) ctor'unu kullan
        User u = new User(username, "$2a$10$dummyhash..............................................", Role.ADMIN);
        u.setId(1L);
        return u;
    }

    @Test void issuedTokenIsLookedUp() {
        SessionStore s = new SessionStore();
        try {
            String t = s.issue(makeUser("alice"));
            assertNotNull(t);
            assertTrue(s.lookup(t).isPresent());
            assertEquals("alice", s.lookup(t).get().getUsername());
        } finally {
            s.shutdown();
        }
    }

    @Test void revokedTokenIsGone() {
        SessionStore s = new SessionStore();
        try {
            String t = s.issue(makeUser("bob"));
            s.revoke(t);
            assertFalse(s.lookup(t).isPresent());
        } finally {
            s.shutdown();
        }
    }

    @Test void expiredTokenIsRejected() throws Exception {
        // Çok kısa absolute TTL — idle kapalı
        SessionStore s = new SessionStore(50L, 0L);
        try {
            String t = s.issue(makeUser("charlie"));
            Thread.sleep(150);
            assertFalse(s.lookup(t).isPresent());
        } finally {
            s.shutdown();
        }
    }

    @Test void idleTimeoutExpiresTokenAfterInactivity() throws Exception {
        // Uzun absolute (1 saat) + kısa idle (80ms)
        SessionStore s = new SessionStore(3_600_000L, 80L);
        try {
            String t = s.issue(makeUser("dora"));
            assertTrue(s.lookup(t).isPresent(), "Yeni token aktif olmalı");
            Thread.sleep(150);  // idle eşiğini aş
            assertFalse(s.lookup(t).isPresent(), "Idle timeout sonrası token düşmeli");
        } finally {
            s.shutdown();
        }
    }

    @Test void activeUseResetsIdleTimer() throws Exception {
        SessionStore s = new SessionStore(3_600_000L, 100L);
        try {
            String t = s.issue(makeUser("eve"));
            // Aktif kullan — her seferinde idle sayacı sıfırlanır
            for (int i = 0; i < 3; i++) {
                Thread.sleep(60);  // idle'dan kısa
                assertTrue(s.lookup(t).isPresent(), "Aktif kullanım idle'ı sıfırlamalı");
            }
        } finally {
            s.shutdown();
        }
    }

    @Test void tokensAreUnique() {
        SessionStore s = new SessionStore();
        try {
            String a = s.issue(makeUser("a"));
            String b = s.issue(makeUser("a"));
            assertNotEquals(a, b);
        } finally {
            s.shutdown();
        }
    }

    @Test void constantTimeEqualsBasics() {
        assertTrue(SessionStore.constantTimeEquals("abc", "abc"));
        assertFalse(SessionStore.constantTimeEquals("abc", "abd"));
        assertFalse(SessionStore.constantTimeEquals("abc", "abcd"));
        assertFalse(SessionStore.constantTimeEquals(null, "abc"));
        assertFalse(SessionStore.constantTimeEquals("abc", null));
    }
}
