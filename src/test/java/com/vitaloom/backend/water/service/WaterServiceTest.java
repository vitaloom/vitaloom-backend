package com.vitaloom.backend.water.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Proxy;

import com.vitaloom.backend.water.model.WaterLog;
import com.vitaloom.backend.water.repository.WaterLogRepository;
import com.vitaloom.backend.water.service.impl.WaterServiceImpl;
import com.vitaloom.backend.water.exception.WaterValidationException;
import com.vitaloom.backend.water.exception.WaterLogDuplicateException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WaterServiceTest {
    List<WaterLog> stored = new ArrayList<>();
    WaterLogRepository logs = (WaterLogRepository) Proxy.newProxyInstance(
            WaterLogRepository.class.getClassLoader(), new Class<?>[]{WaterLogRepository.class}, (proxy, method, args) -> {
                if (method.getName().equals("findByUserIdAndLoggedAtGreaterThanEqualAndLoggedAtLessThanOrderByLoggedAtDesc")) {
                    String userId = (String) args[0];
                    Instant start = (Instant) args[1];
                    Instant end = (Instant) args[2];
                    return stored.stream().filter(log -> log.getUserId().equals(userId))
                            .filter(log -> !log.getLoggedAt().isBefore(start) && log.getLoggedAt().isBefore(end))
                            .sorted((left, right) -> right.getLoggedAt().compareTo(left.getLoggedAt())).toList();
                }
                if (method.getName().equals("save")) {
                    WaterLog log = (WaterLog) args[0]; log.setId("log1"); stored.add(log); return log;
                }
                throw new UnsupportedOperationException(method.getName());
            });
    WaterService service;
    Clock clock = Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void setUp() { stored.clear(); service = new WaterServiceImpl(logs, clock); }

    @Test void dailySummaryAggregatesTheRequestedLocalDayAndKeepsNewestFirst() {
        WaterLog latest = new WaterLog("u1", 250, Instant.parse("2026-11-06T01:00:00Z"));
        WaterLog earlier = new WaterLog("u1", 500, Instant.parse("2026-11-05T18:00:00Z"));
        stored.add(latest);
        stored.add(earlier);
        stored.add(new WaterLog("other-user", 10000, Instant.parse("2026-11-06T02:00:00Z")));
        var summary = service.dailySummary("u1", "2026-11-06", "Asia/Ho_Chi_Minh");
        assertThat(summary.totalMl()).isEqualTo(750);
        assertThat(summary.goalMl()).isEqualTo(2000);
        assertThat(summary.percent()).isEqualTo(37.5);
        assertThat(summary.logs()).extracting("amountMl").containsExactly(250, 500);
    }

    @Test void dailySummaryReturnsValidEmptySummary() {
        var summary = service.dailySummary("u1", "2026-11-06", "UTC");
        assertThat(summary.totalMl()).isZero();
        assertThat(summary.logs()).isEmpty();
    }

    @Test void dailySummaryDoesNotClampPercentageAboveGoal() {
        stored.add(new WaterLog("u1", 2000, Instant.parse("2026-11-06T01:00:00Z")));
        stored.add(new WaterLog("u1", 250, Instant.parse("2026-11-06T02:00:00Z")));
        var summary = service.dailySummary("u1", "2026-11-06", "UTC");
        assertThat(summary.totalMl()).isEqualTo(2250);
        assertThat(summary.percent()).isEqualTo(112.5);
    }

    @Test void weeklyProgressReturnsSevenLocalDaysAndIgnoresOtherUsers() {
        stored.add(new WaterLog("u1", 300, Instant.parse("2026-03-08T06:30:00Z")));
        stored.add(new WaterLog("other-user", 900, Instant.parse("2026-03-08T06:30:00Z")));
        var summary = service.weeklyProgress("u1", "2026-03-02", "America/New_York");
        assertThat(summary.days()).hasSize(7);
        assertThat(summary.days().get(6).date()).isEqualTo("2026-03-08");
        assertThat(summary.days().get(6).totalMl()).isEqualTo(300);
        assertThat(summary.days().subList(0, 6)).allSatisfy(day -> assertThat(day.totalMl()).isZero());
        assertThat(summary.weekEnd()).isEqualTo("2026-03-08");
    }

    @Test void rejectsNonMondayAndInvalidZone() {
        assertThatThrownBy(() -> service.weeklyProgress("u1", "2026-11-03", "UTC"))
                .isInstanceOf(WaterValidationException.class);
        assertThatThrownBy(() -> service.dailySummary("u1", "2026-11-06", "Not/AZone"))
                .isInstanceOf(WaterValidationException.class);
    }

    @Test void createsLogAndRejectsFutureOrOutOfRangeValues() {
        var created = service.create("u1", 250, "2026-11-06T09:15:00+07:00");
        assertThat(created.amountMl()).isEqualTo(250);
        assertThat(created.loggedAt()).isEqualTo(Instant.parse("2026-11-06T02:15:00Z"));
        assertThat(stored.get(0).getUserId()).isEqualTo("u1");
        assertThat(stored.get(0).getLoggedAt()).isEqualTo(Instant.parse("2026-11-06T02:15:00Z"));
        assertThatThrownBy(() -> service.create("u1", 0, "2026-11-06T09:00:00Z"))
                .isInstanceOf(WaterValidationException.class);
        assertThatThrownBy(() -> service.create("u1", 2001, "2026-11-06T09:00:00Z"))
                .isInstanceOf(WaterValidationException.class);
        assertThatThrownBy(() -> service.create("u1", 250, "2026-11-06T10:00:01Z"))
                .isInstanceOf(WaterValidationException.class);
        assertThat(stored).hasSize(1);
    }

    @Test void omittedTimestampUsesServerClockAndExplicitTimestampIsPreserved() {
        var createdNow = service.create("u1", 250, null);
        var createdExplicit = service.create("u1", 100, "2026-11-06T09:59:59.987654321Z");

        assertThat(createdNow.loggedAt()).isEqualTo(Instant.parse("2026-11-06T10:00:00Z"));
        var explicit = Instant.parse("2026-11-06T09:59:59.987654321Z");
        assertThat(createdExplicit.loggedAt()).isEqualTo(explicit);
        assertThat(stored.get(1).getLoggedAt()).isEqualTo(explicit);
        assertThat(stored).hasSize(2);
    }

    @Test void omittedTimestampMatchesMongoMillisecondPrecision() {
        service = new WaterServiceImpl(logs, Clock.fixed(Instant.parse("2026-11-06T10:00:00.000500Z"), ZoneOffset.UTC));

        var created = service.create("u1", 250, null);

        assertThat(created.loggedAt()).isEqualTo(Instant.parse("2026-11-06T10:00:00Z"));
    }

    @Test void futureTimestampHasSpecificValidationMessage() {
        assertThatThrownBy(() -> service.create("u1", 250, "2026-11-06T10:00:00.001Z"))
                .isInstanceOf(WaterValidationException.class)
                .hasMessage("loggedAt cannot be in the future.");
    }

    @Test void createMapsOnlyDuplicateFromWaterUniqueIndex() {
        WaterLogRepository duplicateLogs = (WaterLogRepository) Proxy.newProxyInstance(
                WaterLogRepository.class.getClassLoader(), new Class<?>[]{WaterLogRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        throw new DuplicateKeyException("E11000 index: user_logged_at_unique_idx dup key");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        service = new WaterServiceImpl(duplicateLogs, clock);

        assertThatThrownBy(() -> service.create("u1", 250, "2026-11-06T09:15:00+07:00"))
                .isInstanceOf(WaterLogDuplicateException.class);
    }

    @Test void createPropagatesUnrelatedDuplicateAndDatabaseErrors() {
        for (RuntimeException failure : List.of(
                new DuplicateKeyException("E11000 index: another_unique_idx dup key"),
                new DuplicateKeyException("E11000 index: _id_ dup key: { _id: index: user_logged_at_unique_idx dup key }"),
                new DataAccessResourceFailureException("database unavailable"))) {
            WaterLogRepository failingLogs = (WaterLogRepository) Proxy.newProxyInstance(
                    WaterLogRepository.class.getClassLoader(), new Class<?>[]{WaterLogRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("save")) throw failure;
                        throw new UnsupportedOperationException(method.getName());
                    });
            service = new WaterServiceImpl(failingLogs, clock);

            assertThatThrownBy(() -> service.create("u1", 250, "2026-11-06T09:15:00+07:00"))
                    .isSameAs(failure);
        }
    }

    @Test void waterLogDeclaresUniqueCompoundIndexMetadata() {
        var annotation = WaterLog.class.getAnnotation(org.springframework.data.mongodb.core.index.CompoundIndexes.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).anySatisfy(index -> {
            assertThat(index.name()).isEqualTo(WaterLog.USER_LOGGED_AT_UNIQUE_INDEX);
            assertThat(index.def()).isEqualTo("{'user_id': 1, 'logged_at': 1}");
            assertThat(index.unique()).isTrue();
        });
    }
}
