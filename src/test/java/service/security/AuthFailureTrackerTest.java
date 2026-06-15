package service.security;

import org.junit.jupiter.api.Test;
import service.api.AuthFailureTracker;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFailureTrackerTest {

    @Test void notLockedInitially() {
        AuthFailureTracker t = new AuthFailureTracker();
        try {
            assertFalse(t.isLocked("alice", "1.2.3.4"));
        } finally {
            t.shutdown();
        }
    }

    @Test void locksAfterMaxFailures() {
        AuthFailureTracker t = new AuthFailureTracker(3, TimeUnit.MINUTES.toMillis(5));
        try {
            t.recordFailure("alice", "1.2.3.4");
            t.recordFailure("alice", "1.2.3.4");
            assertFalse(t.isLocked("alice", "1.2.3.4"), "2 fail → henüz kilitli değil");
            t.recordFailure("alice", "1.2.3.4");
            assertTrue(t.isLocked("alice", "1.2.3.4"), "3. fail sonrası kilitlenmeli");
        } finally {
            t.shutdown();
        }
    }

    @Test void successResetsCounter() {
        AuthFailureTracker t = new AuthFailureTracker(3, TimeUnit.MINUTES.toMillis(5));
        try {
            t.recordFailure("alice", "1.2.3.4");
            t.recordFailure("alice", "1.2.3.4");
            t.recordSuccess("alice", "1.2.3.4");
            // Sayaç sıfırlandı; 2 fail daha kilitlemez
            t.recordFailure("alice", "1.2.3.4");
            t.recordFailure("alice", "1.2.3.4");
            assertFalse(t.isLocked("alice", "1.2.3.4"));
        } finally {
            t.shutdown();
        }
    }

    @Test void separateUserIpKeys() {
        AuthFailureTracker t = new AuthFailureTracker(2, TimeUnit.MINUTES.toMillis(5));
        try {
            // Alice'in IP'sinden 2 fail → kilitli
            t.recordFailure("alice", "1.2.3.4");
            t.recordFailure("alice", "1.2.3.4");
            assertTrue(t.isLocked("alice", "1.2.3.4"));
            // Aynı kullanıcı farklı IP → ayrı sayaç
            assertFalse(t.isLocked("alice", "5.6.7.8"));
            // Farklı kullanıcı aynı IP → ayrı sayaç
            assertFalse(t.isLocked("bob", "1.2.3.4"));
        } finally {
            t.shutdown();
        }
    }

    @Test void lockoutExpiresAfterTimeout() throws Exception {
        AuthFailureTracker t = new AuthFailureTracker(2, 100L); // 100 ms lockout
        try {
            t.recordFailure("alice", "1.2.3.4");
            t.recordFailure("alice", "1.2.3.4");
            assertTrue(t.isLocked("alice", "1.2.3.4"));
            Thread.sleep(150);
            assertFalse(t.isLocked("alice", "1.2.3.4"), "Süresi geçen kilit kalkmalı");
        } finally {
            t.shutdown();
        }
    }
}
