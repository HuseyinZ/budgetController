package service.security;

import org.junit.jupiter.api.Test;
import service.api.RateLimiter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RateLimiterTest {

    @Test void capacityAllowsBurst() {
        RateLimiter rl = new RateLimiter("test", 60, 5);  // 60/dk, 5 burst
        try {
            for (int i = 0; i < 5; i++) {
                assertTrue(rl.tryAcquire("k1"), "İlk 5 burst başarılı olmalı");
            }
            // 6. denemede dolu
            assertFalse(rl.tryAcquire("k1"), "6. denemede 429 (token yok)");
        } finally {
            rl.shutdown();
        }
    }

    @Test void differentKeysHaveSeparateBuckets() {
        RateLimiter rl = new RateLimiter("test", 60, 2);
        try {
            assertTrue(rl.tryAcquire("alice"));
            assertTrue(rl.tryAcquire("alice"));
            assertFalse(rl.tryAcquire("alice"), "Alice dolu");
            assertTrue(rl.tryAcquire("bob"), "Bob ayrı bucket");
        } finally {
            rl.shutdown();
        }
    }

    @Test void refillRestoresCapacityOverTime() throws Exception {
        RateLimiter rl = new RateLimiter("test", 600, 2); // dakikada 600 → 100ms'de 1 token
        try {
            assertTrue(rl.tryAcquire("k"));
            assertTrue(rl.tryAcquire("k"));
            assertFalse(rl.tryAcquire("k"));
            Thread.sleep(150);  // 100ms eşiğini aşacak kadar bekle
            assertTrue(rl.tryAcquire("k"), "Refill sonrası token gelmeli");
        } finally {
            rl.shutdown();
        }
    }

    @Test void resetClearsBucket() {
        RateLimiter rl = new RateLimiter("test", 60, 2);
        try {
            rl.tryAcquire("k");
            rl.tryAcquire("k");
            assertFalse(rl.tryAcquire("k"));
            rl.reset("k");
            assertTrue(rl.tryAcquire("k"), "Reset sonrası temiz bucket");
        } finally {
            rl.shutdown();
        }
    }

    @Test void invalidConstructorArgsThrow() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter("x", 10, 0));
    }

    @Test void nullKeyHandledGracefully() {
        RateLimiter rl = new RateLimiter("test", 60, 1);
        try {
            assertTrue(rl.tryAcquire(null), "null key tek bucket'a düşer, ilk istek geçer");
            assertFalse(rl.tryAcquire(null), "Aynı null bucket dolu");
        } finally {
            rl.shutdown();
        }
    }
}
