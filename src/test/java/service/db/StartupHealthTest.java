package service.db;

import DataConnection.DbConfig;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 — retry/timeout davranışı. Sahte saat ve sahte uyku kullanılır:
 * testler gerçekten beklemez, hiçbir veritabanına bağlanmaz.
 */
class StartupHealthTest {

    /** Yalnız "uyku" ile ilerleyen sahte saat. */
    private static final class FakeTime {
        long now;
        final List<Long> sleeps = new ArrayList<>();

        long nowMillis() {
            return now;
        }

        void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }
    }

    private static StartupHealth health(FakeTime t, StartupHealth.Probe probe) {
        return new StartupHealth(StartupHealth.DEFAULT_BUDGET_MS, StartupHealth.DEFAULT_INTERVAL_MS,
                probe, t::sleep, t::nowMillis);
    }

    /** Geçici bağlantı hatası — MySQL henüz ayakta değil. */
    private static SQLException transientFailure() {
        return new SQLNonTransientConnectionException(
                "Communications link failure to jdbc:mysql://127.0.0.1:3306/posdb (user=budget_app)",
                "08S01", 0);
    }

    @Test
    void immediateSuccessProbesOnceAndNeverSleeps() {
        FakeTime t = new FakeTime();
        AtomicInteger probes = new AtomicInteger();

        StartupHealth.Result r = health(t, probes::incrementAndGet).await();

        assertEquals(StartupHealth.Outcome.AVAILABLE, r.outcome());
        assertTrue(r.available());
        assertEquals(1, r.attempts());
        assertEquals(1, probes.get());
        assertEquals(0, t.sleeps.size(), "başarılıysa hiç uyunmamalı");
        assertEquals(0L, r.waitedMillis());
        assertNull(r.userMessage(), "başarıda kullanıcı mesajı olmaz");
    }

    @Test
    void transientFailuresAreRetriedUntilSuccess() {
        FakeTime t = new FakeTime();
        AtomicInteger probes = new AtomicInteger();

        StartupHealth.Result r = health(t, () -> {
            if (probes.incrementAndGet() < 3) {
                throw transientFailure();
            }
        }).await();

        assertEquals(StartupHealth.Outcome.AVAILABLE, r.outcome());
        assertEquals(3, r.attempts());
        assertEquals(2, t.sleeps.size());
        assertEquals(6_000L, r.waitedMillis(), "iki kez 3 sn beklenmeli");
    }

    @Test
    void retryIntervalIsExactlyThreeSeconds() {
        FakeTime t = new FakeTime();
        AtomicInteger probes = new AtomicInteger();

        health(t, () -> {
            if (probes.incrementAndGet() < 5) {
                throw transientFailure();
            }
        }).await();

        assertEquals(4, t.sleeps.size());
        assertTrue(t.sleeps.stream().allMatch(ms -> ms == 3_000L), "her bekleme 3 sn olmalı: " + t.sleeps);
    }

    @Test
    void permanentOutageTimesOutDeterministically() {
        FakeTime t = new FakeTime();
        List<Long> attemptStarts = new ArrayList<>();

        StartupHealth.Result r = health(t, () -> {
            attemptStarts.add(t.now);
            throw transientFailure();
        }).await();

        assertEquals(StartupHealth.Outcome.TIMED_OUT, r.outcome());
        assertFalse(r.available());
        // Denemeler t=0, 3000, ..., 87000 → 30 deneme. t=90000 deadline olduğundan
        // orada yeni yoklama başlatılmaz; bütçenin kalanı beklenerek doldurulur.
        assertEquals(30, r.attempts());
        assertEquals(30, attemptStarts.size());
        assertEquals(0L, attemptStarts.get(0));
        assertEquals(87_000L, attemptStarts.get(29));
        assertTrue(attemptStarts.stream().allMatch(s -> s < 90_000L),
                "deadline'da veya sonrasında yoklama başlamamalı: " + attemptStarts);
        assertEquals(30, t.sleeps.size(), "29 ara bekleme + son bekleme");
        assertTrue(t.sleeps.stream().allMatch(ms -> ms == 3_000L), t.sleeps.toString());
        assertEquals(90_000L, r.waitedMillis(), "süre bütçesi erken bırakılmamalı");
        assertEquals(StartupHealth.MESSAGE_TIMEOUT, r.userMessage());
    }

    @Test
    void slowProbesKeepTheThreeSecondCadence() {
        FakeTime t = new FakeTime();
        List<Long> attemptStarts = new ArrayList<>();
        AtomicInteger probes = new AtomicInteger();

        // Hikari connectionTimeout'u kadar süren yoklama: 2500 ms
        StartupHealth.Result r = health(t, () -> {
            attemptStarts.add(t.now);
            t.now += 2_500L;
            if (probes.incrementAndGet() < 3) {
                throw transientFailure();
            }
        }).await();

        assertEquals(StartupHealth.Outcome.AVAILABLE, r.outcome());
        assertEquals(List.of(0L, 3_000L, 6_000L), attemptStarts,
                "tempo deneme başlangıcından başlangıcına 3 sn olmalı");
        assertEquals(List.of(500L, 500L), t.sleeps,
                "yoklama 2500 ms sürdüyse yalnız 500 ms uyunmalı");
        assertEquals(8_500L, r.waitedMillis());
    }

    @Test
    void slowProbesStillGetAllAttemptsWithinTheBudget() {
        FakeTime t = new FakeTime();
        List<Long> attemptStarts = new ArrayList<>();

        StartupHealth.Result r = health(t, () -> {
            attemptStarts.add(t.now);
            t.now += 2_500L;
            throw transientFailure();
        }).await();

        assertEquals(StartupHealth.Outcome.TIMED_OUT, r.outcome());
        // Regresyon: yavaş yoklama + sabit 3 sn uyku ~5,5 sn tempo ve 17 deneme veriyordu.
        assertEquals(30, r.attempts());
        assertEquals(0L, attemptStarts.get(0));
        assertEquals(3_000L, attemptStarts.get(1));
        assertEquals(87_000L, attemptStarts.get(attemptStarts.size() - 1));
        assertTrue(attemptStarts.stream().allMatch(s -> s < 90_000L),
                "deadline'da veya sonrasında yeni yoklama başlamamalı");
        // 29 ara bekleme (2500 ms yoklamadan sonra 500 ms) + son 500 ms ile 90000'e tamamlanır
        assertEquals(30, t.sleeps.size());
        assertTrue(t.sleeps.stream().allMatch(ms -> ms == 500L), t.sleeps.toString());
        assertEquals(90_000L, r.waitedMillis(), "süre bütçesi erken bırakılmamalı");
    }

    @Test
    void aProbeOutlivingTheDeadlineDoesNotStartAnother() {
        FakeTime t = new FakeTime();
        List<Long> attemptStarts = new ArrayList<>();

        // Patolojik yavaş yoklama: 7000 ms. Başlangıçlar 0, 7000, ..., 84000;
        // 84000'de başlayan yoklama 91000'de biter — orada YENİ yoklama açılmamalı.
        StartupHealth.Result r = health(t, () -> {
            attemptStarts.add(t.now);
            t.now += 7_000L;
            throw transientFailure();
        }).await();

        assertEquals(StartupHealth.Outcome.TIMED_OUT, r.outcome());
        assertEquals(13, r.attempts());
        assertEquals(13, attemptStarts.size());
        assertEquals(0L, attemptStarts.get(0));
        assertEquals(7_000L, attemptStarts.get(1));
        assertEquals(84_000L, attemptStarts.get(attemptStarts.size() - 1));
        assertTrue(attemptStarts.stream().allMatch(s -> s < 90_000L),
                "deadline'da veya sonrasında yoklama başlamamalı: " + attemptStarts);
        assertFalse(attemptStarts.contains(91_000L), "91000'de yeni yoklama olmamalı");
        assertEquals(0, t.sleeps.size(), "aralıktan uzun yoklamadan sonra beklenmez");
        assertEquals(91_000L, r.waitedMillis());
        assertEquals(StartupHealth.MESSAGE_TIMEOUT, r.userMessage());
    }

    @Test
    void probeSlowerThanTheIntervalRetriesWithoutSleeping() {
        FakeTime t = new FakeTime();
        List<Long> attemptStarts = new ArrayList<>();
        AtomicInteger probes = new AtomicInteger();

        StartupHealth.Result r = health(t, () -> {
            attemptStarts.add(t.now);
            t.now += 4_000L;
            if (probes.incrementAndGet() < 3) {
                throw transientFailure();
            }
        }).await();

        assertEquals(StartupHealth.Outcome.AVAILABLE, r.outcome());
        assertEquals(List.of(0L, 4_000L, 8_000L), attemptStarts);
        assertEquals(0, t.sleeps.size(), "aralıktan uzun yoklamadan sonra beklenmez");
    }

    @Test
    void missingConfigurationFailsImmediatelyWithoutRetrying() {
        FakeTime t = new FakeTime();
        AtomicInteger probes = new AtomicInteger();

        StartupHealth.Result r = health(t, () -> {
            probes.incrementAndGet();
            throw new DbConfig.MissingConfigException(List.of(DbConfig.KEY_PASSWORD));
        }).await();

        assertEquals(StartupHealth.Outcome.NOT_RETRYABLE, r.outcome());
        assertEquals(1, r.attempts());
        assertEquals(1, probes.get(), "yapılandırma hatası 90 sn boyunca tekrarlanmamalı");
        assertEquals(0, t.sleeps.size());
        assertEquals(StartupHealth.MESSAGE_CONFIG, r.userMessage());
    }

    @Test
    void wrongCredentialsAreNotRetried() {
        FakeTime t = new FakeTime();

        StartupHealth.Result r = health(t, () -> {
            throw new SQLInvalidAuthorizationSpecException("Access denied for user 'budget_app'", "28000", 1045);
        }).await();

        assertEquals(StartupHealth.Outcome.NOT_RETRYABLE, r.outcome());
        assertEquals(1, r.attempts());
        assertEquals(0, t.sleeps.size());
    }

    @Test
    void retryClassificationFollowsSqlState() {
        assertTrue(StartupHealth.isRetryable(transientFailure()), "08xxx geçici");
        assertTrue(StartupHealth.isRetryable(
                new SQLTransientConnectionException("pool timeout", null, 0)), "havuz zaman aşımı geçici");
        assertTrue(StartupHealth.isRetryable(
                new RuntimeException("wrapped", transientFailure())), "sarılmış geçici hata");
        assertFalse(StartupHealth.isRetryable(
                new SQLException("Unknown database 'posdb'", "42000", 1049)), "42xxx kalıcı");
        assertFalse(StartupHealth.isRetryable(
                new SQLException("Unknown database", "3D000", 1049)), "3D000 kalıcı");
        assertFalse(StartupHealth.isRetryable(
                new IllegalStateException("config")), "SQL olmayan hata kalıcı sayılır");
    }

    @Test
    void unexpectedErrorsPropagateInsteadOfBecomingAResult() {
        FakeTime t = new FakeTime();
        AtomicInteger probes = new AtomicInteger();

        AssertionError thrown = assertThrows(AssertionError.class, () -> health(t, () -> {
            probes.incrementAndGet();
            throw new AssertionError("programming error");
        }).await());

        assertEquals("programming error", thrown.getMessage());
        assertEquals(1, probes.get(), "beklenmeyen Error yeniden denenmemeli");
        assertEquals(0, t.sleeps.size(), "beklenmeyen Error yutulup beklemeye dönüşmemeli");
    }

    @Test
    void lazyInitializationErrorIsHandledAsConfigurationFailure() {
        FakeTime t = new FakeTime();

        StartupHealth.Result r = health(t, () -> {
            throw new ExceptionInInitializerError(
                    new DbConfig.MissingConfigException(List.of(DbConfig.KEY_URL)));
        }).await();

        assertEquals(StartupHealth.Outcome.NOT_RETRYABLE, r.outcome());
        assertEquals(1, r.attempts());
        assertEquals(0, t.sleeps.size());
        assertEquals(StartupHealth.MESSAGE_CONFIG, r.userMessage());
    }

    @Test
    void userFacingMessagesCarryNoTechnicalDetail() {
        FakeTime t = new FakeTime();

        StartupHealth.Result timedOut = health(t, () -> {
            throw new SQLNonTransientConnectionException(
                    "link failure jdbc:mysql://127.0.0.1/posdb?user=budget_app&password=s3cr3t",
                    "08S01", 1042);
        }).await();

        for (String message : List.of(timedOut.userMessage(),
                StartupHealth.MESSAGE_WAITING, StartupHealth.MESSAGE_CONFIG)) {
            assertFalse(message.contains("s3cr3t"), message);
            assertFalse(message.contains("jdbc:"), message);
            assertFalse(message.contains("SQLState"), message);
            assertFalse(message.contains("08S01"), message);
            assertFalse(message.contains("Exception"), message);
            assertFalse(message.contains("budget_app"), message);
        }
        assertEquals("Veritabanı bekleniyor…", StartupHealth.MESSAGE_WAITING);
        assertEquals("Veritabanı servisi çalışmıyor — yönetici: MySQL servisini başlatın",
                StartupHealth.MESSAGE_TIMEOUT);
    }
}
