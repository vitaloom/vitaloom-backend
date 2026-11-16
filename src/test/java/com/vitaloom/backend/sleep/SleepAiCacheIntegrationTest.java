package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.contract.SleepGoalReadQuery;
import com.vitaloom.backend.sleep.contract.SleepMainReadQuery;
import com.vitaloom.backend.sleep.contract.dto.MainSleepDay;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsight;
import com.vitaloom.backend.sleep.contract.dto.MainSleepInsightWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepWindow;
import com.vitaloom.backend.sleep.contract.dto.MainSleepRangeWindow;
import com.vitaloom.backend.sleep.contract.dto.SleepGoalWindow;
import com.vitaloom.backend.sleep.controller.SleepStoryController;
import com.vitaloom.backend.sleep.controller.exceptionhandler.SleepExceptionHandler;
import com.vitaloom.backend.sleep.repository.RedisSleepAiCache;
import com.vitaloom.backend.sleep.service.SleepAiCache;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import com.vitaloom.backend.sleep.service.SleepStoryService.Consent;
import com.vitaloom.backend.sleep.service.SleepStoryService.ConsentStore;
import com.vitaloom.backend.sleep.service.SleepStoryService.Evidence;
import com.vitaloom.backend.sleep.service.SleepStoryService.Provider;
import com.vitaloom.backend.statistics.service.impl.SleepInsightsStatisticsServiceImpl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfSystemProperty(named = "sleep.cache.redis.port", matches = "[0-9]+")
class SleepAiCacheIntegrationTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private final Set<String> ownedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeEach
    void connectToDisposableLoopbackRedis() {
        String host = System.getProperty("sleep.cache.redis.host", "127.0.0.1");
        assertThat(host).isEqualTo("127.0.0.1");
        connection = new LettuceConnectionFactory(host, Integer.parseInt(System.getProperty("sleep.cache.redis.port")));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
    }

    @AfterEach
    void removeOnlyKeysCreatedByThisTest() {
        if (redis != null) ownedKeys.forEach(redis::delete);
        if (connection != null) {
            connection.destroy();
        }
    }

    @Test
    void httpStoryAndExplanationReuseDistinctRedisValuesAndRespectFreshnessAndConsent() throws Exception {
        String owner = "cache-test-" + UUID.randomUUID();
        Harness harness = new Harness(owner);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(harness.service))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        var principal = new TestingAuthenticationToken(owner, "unused");

        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"));
        String storyKey = harness.cache.keys.stream().filter(key -> key.contains(":story:")).findFirst().orElseThrow();
        assertThat(redis.getExpire(storyKey)).isBetween(86_000L, 86_400L);
        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"));
        assertThat(harness.generated.get()).isEqualTo(1);

        mvc.perform(post("/api/sleep/ai/explanations").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"));
        String explanationKey = harness.cache.keys.stream().filter(key -> key.contains(":explanation:")).findFirst().orElseThrow();
        assertThat(explanationKey).isNotEqualTo(storyKey);
        assertThat(redis.getExpire(explanationKey)).isBetween(86_000L, 86_400L);
        mvc.perform(post("/api/sleep/ai/explanations").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"));
        assertThat(harness.explained.get()).isEqualTo(1);

        String oldStoryKey = storyKey;
        harness.changeFeeling("session-2", "TIRED");
        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"))
                .andExpect(jsonPath("$.notice").exists());
        assertThat(harness.generated.get()).isEqualTo(1);
        harness.advance(Duration.ofMinutes(15));
        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"));
        String freshStoryKey = harness.cache.keys.stream().filter(key -> key.contains(":story:"))
                .filter(key -> !key.equals(oldStoryKey)).findFirst().orElseThrow();
        assertThat(redis.hasKey(oldStoryKey)).isTrue();
        assertThat(freshStoryKey).isNotEqualTo(oldStoryKey);

        harness.consent.set(new Consent(false, "revoked"));
        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSENT_REQUIRED"));
        mvc.perform(post("/api/sleep/ai/explanations").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSENT_REQUIRED"));
        assertThat(redis.hasKey(oldStoryKey)).isTrue();
        assertThat(redis.hasKey(freshStoryKey)).isTrue();
        assertThat(redis.hasKey(explanationKey)).isTrue();

        harness.consent.set(new Consent(true, "revision-2"));
        String expiring = freshStoryKey;
        redis.expire(expiring, Duration.ofMillis(350));
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(expiring)) && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(redis.hasKey(expiring)).isFalse();
        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"));
        assertThat(harness.generated.get()).isEqualTo(2);
        harness.advance(Duration.ofMinutes(15));
        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORY"));
        assertThat(harness.generated.get()).isEqualTo(3);

        mvc.perform(post("/api/sleep/ai/explanations").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"));
        assertThat(harness.explained.get()).isEqualTo(2);
        String evicted = harness.cache.keys.stream().filter(key -> key.contains(":explanation:"))
                .filter(key -> redis.hasKey(key)).reduce((first, second) -> second).orElseThrow();
        redis.delete(evicted);
        mvc.perform(post("/api/sleep/ai/explanations").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"));
        assertThat(harness.explained.get()).isEqualTo(2);
        harness.advance(Duration.ofMinutes(5));
        mvc.perform(post("/api/sleep/ai/explanations").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"));
        assertThat(harness.explained.get()).isEqualTo(3);
    }

    @Test
    void consentWithdrawalDuringCacheReadSuppressesCachedOutputAndKeepsEntry() throws Exception {
        String owner = "cache-race-" + UUID.randomUUID();
        Harness harness = new Harness(owner);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(harness.service))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        var principal = new TestingAuthenticationToken(owner, "unused");
        mvc.perform(get("/api/sleep/recent/story").principal(principal)).andExpect(status().isOk());
        String key = harness.cache.keys.getFirst();
        harness.cache.afterRead = ignored -> harness.consent.set(new Consent(false, "withdrawn"));
        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.headline").doesNotExist());
        assertThat(harness.generated.get()).isEqualTo(1);
        assertThat(redis.hasKey(key)).isTrue();
    }

    @Test
    void staleEvidenceAndWindowDuringCacheHitsAreNeverServedOrRewritten() throws Exception {
        String owner = "cache-stale-" + UUID.randomUUID();
        Harness harness = new Harness(owner);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(harness.service))
                .setControllerAdvice(new SleepExceptionHandler()).build();
        var principal = new TestingAuthenticationToken(owner, "unused");
        mvc.perform(get("/api/sleep/recent/story").principal(principal)).andExpect(status().isOk());
        String storyKey = harness.cache.keys.getFirst();
        harness.cache.afterRead = ignored -> harness.now.set(TODAY.plusDays(1).atTime(12, 0).atZone(ZONE).toInstant());
        mvc.perform(get("/api/sleep/recent/story").principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FALLBACK"));
        assertThat(redis.hasKey(storyKey)).isTrue();
        assertThat(harness.generated.get()).isEqualTo(1);

        harness.now.set(TODAY.atTime(12, 0).atZone(ZONE).toInstant());
        harness.cache.afterRead = ignored -> {};
        mvc.perform(post("/api/sleep/ai/explanations").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPLANATION"));
        String explanationKey = harness.cache.keys.stream().filter(key -> key.contains(":explanation:"))
                .reduce((first, second) -> second).orElseThrow();
        harness.cache.afterRead = ignored -> harness.changeFeeling("session-2", "TIRED");
        mvc.perform(post("/api/sleep/ai/explanations").principal(principal).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"B01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.observation").doesNotExist());
        assertThat(redis.hasKey(explanationKey)).isTrue();
        assertThat(harness.explained.get()).isEqualTo(1);
    }

    @Test
    void staleOrWithdrawnProviderResultsAreNotCachedAndOwnerNamespacingIsIsolated() {
        String owner = "cache-owner-" + UUID.randomUUID();
        Harness first = new Harness(owner);
        first.beforeProvider = ignored -> first.changeFeeling("session-2", "TIRED");
        assertThat(first.service.story(owner).status()).isEqualTo("FALLBACK");
        String staleKey = first.cache.keys.getFirst();
        assertThat(redis.hasKey(staleKey)).isFalse();

        Harness second = new Harness(owner + "-other");
        assertThat(second.service.story(owner + "-other").status()).isEqualTo("STORY");
        String otherKey = second.cache.keys.getFirst();
        assertThat(otherKey).isNotEqualTo(staleKey);

        String revokedOwner = owner + "-revoked";
        Harness revoked = new Harness(revokedOwner);
        revoked.beforeProvider = ignored -> revoked.consent.set(new Consent(false, "withdrawn-during-call"));
        assertThat(revoked.service.story(revokedOwner).status()).isEqualTo("CONSENT_REQUIRED");
        String revokedKey = revoked.cache.keys.getFirst();
        assertThat(redis.hasKey(revokedKey)).isFalse();
        assertThat(revoked.generated.get()).isEqualTo(1);
    }

    @Test
    void unavailableRedisReturnsCurrentFallbackWithoutCallingFakeProvider() throws Exception {
        int unusedPort;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200))
                .shutdownTimeout(Duration.ZERO).clientOptions(ClientOptions.builder().autoReconnect(false)
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build()).build()).build();
        var unavailable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", unusedPort), client);
        unavailable.afterPropertiesSet();
        try {
            var template = new StringRedisTemplate(unavailable);
            String owner = "cache-offline-" + UUID.randomUUID();
            Harness harness = new Harness(owner, new RedisSleepAiCache(template, JsonMapper.builder().build()));
            assertThat(harness.service.story(owner).status()).isEqualTo("FALLBACK");
            assertThat(harness.service.explanation(owner, "B01", null).status()).isEqualTo("FALLBACK");
            assertThat(harness.generated.get()).isZero();
            assertThat(harness.explained.get()).isZero();
        } finally {
            unavailable.destroy();
        }
    }

    @Test
    void explanationWithdrawalOnHitAndDuringGenerationNeverPublishesOrWrites() {
        String owner = "cache-explanation-consent-" + UUID.randomUUID();
        Harness harness = new Harness(owner);
        assertThat(harness.service.explanation(owner, "B01", null).status()).isEqualTo("EXPLANATION");
        String key = harness.cache.keys.getFirst();
        harness.cache.afterRead = ignored -> harness.consent.set(new Consent(true, "re-enabled"));
        assertThat(harness.service.explanation(owner, "B01", null).status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(harness.explained.get()).isEqualTo(1);

        Harness generating = new Harness(owner + "-generating");
        generating.beforeProvider = ignored -> generating.consent.set(new Consent(false, "withdrawn"));
        assertThat(generating.service.explanation(owner + "-generating", "B01", null).status()).isEqualTo("CONSENT_REQUIRED");
        assertThat(redis.hasKey(generating.cache.keys.getFirst())).isFalse();
    }

    private final class Harness {
        private final AtomicReference<List<MainSleepInsight>> rows = new AtomicReference<>(fixtureRows());
        private final AtomicReference<Consent> consent = new AtomicReference<>(new Consent(true, "revision-1"));
        private final AtomicInteger generated = new AtomicInteger();
        private final AtomicInteger explained = new AtomicInteger();
        private final AtomicReference<Instant> now = new AtomicReference<>(TODAY.atTime(12, 0).atZone(ZONE).toInstant());
        private volatile Consumer<String> beforeProvider = ignored -> {};
        private final TrackingCache cache;
        private final SleepStoryService service;

        private Harness(String owner) {
            this(owner, new RedisSleepAiCache(redis, JsonMapper.builder().build()));
        }

        private Harness(String owner, SleepAiCache delegate) {
            SleepMainReadQuery source = mock(SleepMainReadQuery.class);
            when(source.readWindow(eq(owner), any(), any())).thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                return new MainSleepWindow(true, rows.get().stream().filter(row -> between(row.day(), from, to))
                        .map(row -> new MainSleepDay(row.id(), row.day(), row.minutes())).toList());
            });
            when(source.readInsightWindow(eq(owner), any(), any())).thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                return new MainSleepInsightWindow(true, rows.get().stream().filter(row -> between(row.day(), from, to)).toList());
            });
            SleepGoalReadQuery goals = mock(SleepGoalReadQuery.class);
            when(source.readRangeWindow(eq(owner), any(), any())).thenReturn(new MainSleepRangeWindow(true, List.of()));
            when(goals.readGoalWindow(eq(owner), any(), any())).thenAnswer(call -> new SleepGoalWindow(true, Map.of()));
            Clock mutableClock = new Clock() {
                @Override public ZoneId getZone() { return ZONE; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return now.get(); }
            };
            var statistics = new SleepInsightsStatisticsServiceImpl(source, goals, mutableClock);
            ConsentStore consentStore = new ConsentStore() {
                @Override public Consent read(String ignored) { return consent.get(); }
                @Override public void write(String ignored, boolean enabled) { consent.set(new Consent(enabled, UUID.randomUUID().toString())); }
            };
            Provider fake = new Provider() {
                @Override public SleepStoryService.Story generate(Evidence evidence) {
                    generated.incrementAndGet();
                    beforeProvider.accept("story");
                    return SleepStoryService.preview(evidence);
                }
                @Override public SleepStoryService.Explanation explain(Evidence evidence) {
                    explained.incrementAndGet();
                    beforeProvider.accept("explanation");
                    return SleepStoryService.explanationPreview(evidence);
                }
            };
            ObjectProvider<Provider> providerLookup = mock(ObjectProvider.class);
            when(providerLookup.getIfAvailable()).thenReturn(fake);
            cache = new TrackingCache(delegate);
            service = new SleepStoryService(consentStore, statistics, providerLookup, cache, mutableClock);
        }

        private void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }

        private void changeFeeling(String id, String feeling) {
            rows.updateAndGet(current -> current.stream().map(row -> row.id().equals(id)
                    ? new MainSleepInsight(row.id(), row.day(), row.minutes(), feeling, row.confirmedAt()) : row).toList());
        }
    }

    private final class TrackingCache implements SleepAiCache {
        private final SleepAiCache delegate;
        private final List<String> keys = new ArrayList<>();
        private volatile Consumer<String> afterRead = ignored -> {};
        private TrackingCache(SleepAiCache delegate) { this.delegate = delegate; }
        @Override public SleepStoryService.Story readStory(String key) {
            keys.add(key); SleepStoryService.Story value = delegate.readStory(key); afterRead.accept(key); return value;
        }
        @Override public SleepStoryService.Explanation readExplanation(String key) {
            keys.add(key); SleepStoryService.Explanation value = delegate.readExplanation(key); afterRead.accept(key); return value;
        }
        @Override public void writeStory(String key, SleepStoryService.Story value) { keys.add(key); ownedKeys.add(key); delegate.writeStory(key, value); }
        @Override public void writeExplanation(String key, SleepStoryService.Explanation value) { keys.add(key); ownedKeys.add(key); delegate.writeExplanation(key, value); }
        @Override public void writeStory(String key, SleepStoryService.Story value, java.util.function.BooleanSupplier mayDispatch) {
            delegate.writeStory(key, value, () -> {
                boolean allowed = mayDispatch.getAsBoolean();
                if (allowed) { keys.add(key); ownedKeys.add(key); }
                return allowed;
            });
        }
        @Override public void writeExplanation(String key, SleepStoryService.Explanation value, java.util.function.BooleanSupplier mayDispatch) {
            delegate.writeExplanation(key, value, () -> {
                boolean allowed = mayDispatch.getAsBoolean();
                if (allowed) { keys.add(key); ownedKeys.add(key); }
                return allowed;
            });
        }
    }

    private static List<MainSleepInsight> fixtureRows() {
        List<MainSleepInsight> result = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LocalDate day = TODAY.minusDays(i + 1);
            result.add(new MainSleepInsight("session-" + i, day, 420 + i, "COMFORTABLE",
                    day.atStartOfDay(ZONE).toInstant()));
        }
        return List.copyOf(result);
    }

    private static boolean between(LocalDate value, LocalDate from, LocalDate to) {
        return !value.isBefore(from) && !value.isAfter(to);
    }
}
