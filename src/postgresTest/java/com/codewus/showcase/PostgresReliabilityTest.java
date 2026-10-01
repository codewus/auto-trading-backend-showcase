package com.codewus.showcase;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.codewus.showcase.exchange.ExchangeOrderResult;
import com.codewus.showcase.exchange.ExchangeAdapter;
import com.codewus.showcase.exchange.FakeExchangeAdapter;
import com.codewus.showcase.order.*;
import com.codewus.showcase.order.model.*;
import com.codewus.showcase.persistence.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:${SHOWCASE_DB_PORT:15432}/showcase",
        "spring.datasource.username=showcase", "server.address=127.0.0.1" })
class PostgresReliabilityTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderMapper orders;
    @Autowired FillMapper fills;
    @Autowired LegacyTradeMapper legacy;
    @Autowired EventMapper events;
    @Autowired ApplicationMapper applications;
    @Autowired OrderService service;
    @Autowired FillRecorder recorder;
    @Autowired FillApplicationService application;
    @Autowired RecoveryService recovery;
    @Autowired OrderHistoryService history;
    @Autowired FakeExchangeAdapter exchange;
    @Autowired PlatformTransactionManager manager;
    @LocalServerPort int port;
    OrderCommand command;

    @BeforeEach void prepareDedicatedDemoDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("showcase");
        jdbc.execute("TRUNCATE application_ledger, event, fill, submit_claim, local_state, demo_order, legacy_trade RESTART IDENTITY CASCADE");
        // Distinct synthetic action avoids resetting the shared adapter across tests.
        command = command("action-" + java.util.UUID.randomUUID(), "DEMOUSDT", "3.25");
    }

    @Test void realMappersPersistIntentFillLegacyLedgerAndEvents() {
        var view = service.submit(command);
        assertThat(view.order().status()).isEqualTo(OrderIntent.Status.CONFIRMED);
        assertThat(view.fill().legacyTradeId()).isNotNull();
        assertThat(view.locallyApplied()).isTrue();
        assertThat(view.eventCount()).isEqualTo(2);
        assertThat(count("legacy_trade")).isEqualTo(1);
        assertThat(applications.state(command.actorId(), command.symbol()).appliedQuantity()).isEqualByComparingTo("3.25");
    }

    @Test void realUniqueConstraintsRejectDuplicateFillEventAndLedger() {
        var view = service.submit(command);
        long fillId = view.fill().id();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO application_ledger(fill_id) VALUES (?)", fillId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO event(fill_id,type) VALUES (?,'FILL_RECORDED')", fillId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fill(order_id,actor_id,symbol,exchange_order_id,side,quantity,filled_at) "
                + "SELECT order_id,actor_id,symbol,exchange_order_id,side,quantity,filled_at FROM fill WHERE id=?", fillId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("fill")).isEqualTo(1);
    }

    @Test void concurrentClaimElectsExactlyOneExecutor() throws Exception {
        var intent = intent(command);
        var outcomes = race(8, () -> orders.claim(intent));
        assertThat(outcomes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(count("submit_claim")).isEqualTo(1);
    }

    @Test void concurrentLogicalSubmissionsCallExchangeOnce() throws Exception {
        // Repeat different logical actions to expose speculative inserts across multiple unique indexes.
        for (int round = 0; round < 10; round++) {
            var current = command("race-" + round, "DEMOUSDT", "1");
            race(8, () -> service.submit(current));
            recovery.recover(current.actorId());
            assertThat(exchange.submitCount(current.clientOrderId())).isEqualTo(1);
            assertThat(count("demo_order")).isEqualTo(round + 1);
            assertThat(count("fill")).isEqualTo(round + 1);
            assertThat(count("application_ledger")).isEqualTo(round + 1);
            assertThat(applications.state(current.actorId(), current.symbol()).appliedFillCount()).isEqualTo(round + 1);
        }
    }

    @Test void lostResponseUsesClientIdQueryAndNeverPostsAgain() {
        exchange.selectScenario(command.actorId(), command.actionId(), FakeExchangeAdapter.Scenario.RESPONSE_LOST);
        var view = service.submit(command);
        service.submit(command);
        recovery.recover(command.actorId());
        assertThat(view.fill()).isNotNull();
        assertThat(exchange.queryCount(command.clientOrderId())).isEqualTo(1);
        assertThat(exchange.submitCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void durableClaimBeforeDispatchCrashPreventsAutomaticResubmit() {
        var intent = intent(command);
        assertThat(orders.claim(intent)).isEqualTo(1);
        var replacement = replacementService();
        replacement.submit(command);
        new RecoveryService(orders, replacement).recover(command.actorId());
        assertThat(exchange.submitCount(command.clientOrderId())).isZero();
        assertThat(exchange.queryCount(command.clientOrderId())).isEqualTo(2);
        assertThat(count("fill")).isZero();
    }

    @Test void permanentlyUnknownResultRemainsProtectedAcrossRetries() {
        exchange.selectScenario(command.actorId(), command.actionId(), FakeExchangeAdapter.Scenario.UNKNOWN);
        service.submit(command);
        service.submit(command);
        recovery.recover(command.actorId());
        assertThat(orders.findAction(command.actorId(), command.actionId()).status()).isEqualTo(OrderIntent.Status.UNCERTAIN);
        assertThat(exchange.submitCount(command.clientOrderId())).isEqualTo(1);
        assertThat(count("fill")).isZero();
        assertThat(count("local_state")).isZero();
    }

    @Test void repeatedResultAndConcurrentFillInsertProduceOneFill() throws Exception {
        var intent = intent(command);
        ExchangeOrderResult result = exchange.submit(intent);
        race(6, () -> recorder.record(intent, result));
        assertThat(count("fill")).isEqualTo(1);
        assertThat(count("legacy_trade")).isEqualTo(1);
        assertThat(recorder.record(intent, exchange.query(intent)).id()).isEqualTo(fills.findByOrder(intent.id()).id());
    }

    @Test void eventSqlFailurePreservesCommittedFillAndRetriesOnlyMissingEvent() {
        jdbc.execute("CREATE FUNCTION demo_event_fault() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.type='ORDER_CONFIRMED' THEN RAISE EXCEPTION 'synthetic event failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER demo_event_fault BEFORE INSERT ON event FOR EACH ROW EXECUTE FUNCTION demo_event_fault()");
        OrderView first;
        try {
            first = service.submit(command);
            assertThat(first.eventCount()).isEqualTo(1);
            assertThat(count("fill")).isEqualTo(1);
            assertThat(first.locallyApplied()).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER demo_event_fault ON event");
            jdbc.execute("DROP FUNCTION demo_event_fault()");
        }
        long existingEvent = jdbc.queryForObject("SELECT id FROM event WHERE type='FILL_RECORDED'", Long.class);
        recovery.recover(command.actorId());
        recovery.recover(command.actorId());
        assertThat(events.count(first.fill().id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT id FROM event WHERE type='FILL_RECORDED'", Long.class)).isEqualTo(existingEvent);
        assertThat(applications.state(command.actorId(), command.symbol()).appliedFillCount()).isEqualTo(1);
    }

    @Test void localFailureRollsBackBothStateAndApplicationMarker() {
        FillRecord fill = confirmedUnapplied(command);
        ApplicationMapper failing = spy(applications);
        doAnswer(call -> { applications.markApplied(fill.id()); throw new IllegalStateException("synthetic failure after marker"); })
                .when(failing).markApplied(fill.id());
        assertThatThrownBy(() -> new FillApplicationService(failing, manager).apply(fill)).isInstanceOf(IllegalStateException.class);
        assertThat(count("application_ledger")).isZero();
        assertThat(count("local_state")).isZero();
        assertThat(count("fill")).isEqualTo(1);
        recovery.recover(command.actorId());
        assertThat(count("application_ledger")).isEqualTo(1);
        assertThat(applications.state(command.actorId(), command.symbol()).appliedQuantity()).isEqualByComparingTo(command.quantity());
    }

    @Test void repeatedRecoveryAndServiceReplacementApplyOnce() {
        var fill = confirmedUnapplied(command);
        OrderService replacement = replacementService();
        var replacementRecovery = new RecoveryService(orders, replacement);
        replacementRecovery.recover(command.actorId());
        replacementRecovery.recover(command.actorId());
        assertThat(exchange.query(orders.find(command.actorId(), fill.orderId())).confirmed()).isTrue();
        assertThat(applications.state(command.actorId(), command.symbol()).appliedFillCount()).isEqualTo(1);
        assertThat(events.count(fill.id())).isEqualTo(2);
    }

    @Test void missingFillIsRecoveredByQueryAfterReplacingServiceInstances() {
        var intent = intent(command);
        orders.claim(intent);
        exchange.submit(intent); // Exchange accepted; local Fill write never happened.
        assertThat(count("fill")).isZero();
        var recovered = new RecoveryService(orders, replacementService()).recover(command.actorId()).get(0);
        assertThat(recovered.fill()).isNotNull();
        assertThat(recovered.locallyApplied()).isTrue();
        assertThat(recovered.eventCount()).isEqualTo(2);
        assertThat(count("legacy_trade")).isEqualTo(1);
        assertThat(exchange.queryCount(command.clientOrderId())).isEqualTo(1);
        assertThat(exchange.submitCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void exchangeSubmitAndQueryRunOutsideDatabaseTransaction() {
        exchange.selectScenario(command.actorId(), command.actionId(), FakeExchangeAdapter.Scenario.RESPONSE_LOST);
        ExchangeAdapter probe = new ExchangeAdapter() {
            @Override public ExchangeOrderResult submit(OrderIntent intent) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return exchange.submit(intent);
            }
            @Override public ExchangeOrderResult query(OrderIntent intent) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return exchange.query(intent);
            }
        };
        new OrderService(orders, fills, applications, events, probe, recorder, application, manager).submit(command);
        assertThat(count("submit_claim")).isEqualTo(1);
        assertThat(count("fill")).isEqualTo(1);
    }

    @Test void concurrentRecoveryAppliesOnce() throws Exception {
        confirmedUnapplied(command);
        race(8, () -> recovery.recover(command.actorId()));
        assertThat(count("application_ledger")).isEqualTo(1);
        assertThat(applications.state(command.actorId(), command.symbol()).appliedFillCount()).isEqualTo(1);
        assertThat(count("event")).isEqualTo(2);
    }

    @Test void differentFillsForSameLocalStateAreBothAppliedWithoutLostUpdates() throws Exception {
        confirmedUnapplied(command);
        confirmedUnapplied(command("second-action", command.symbol(), "1.75"));
        race(6, () -> recovery.recover(command.actorId()));
        assertThat(applications.state(command.actorId(), command.symbol()).appliedFillCount()).isEqualTo(2);
        assertThat(applications.state(command.actorId(), command.symbol()).appliedQuantity()).isEqualByComparingTo("5.00");
    }

    @Test void compatibilityHistoryPrefersNewAndScopesIdentityBeforeSortingLimit() {
        jdbc.update("INSERT INTO legacy_trade(actor_id,symbol,exchange_order_id,side,quantity,occurred_at) VALUES "
                + "('demo-actor','DEMOUSDT','fx-legacy-only','BUY',1,'2026-01-01T00:00:00Z'),"
                + "('demo-actor','OTHERUSDT','fx-shared','BUY',1,'2026-01-01T00:00:02Z'),"
                + "('demo-other','DEMOUSDT','fx-shared','BUY',1,'2026-01-01T00:00:04Z')");
        var newOnly = intent(command("new-only", "DEMOUSDT", "1"));
        var newResult = result(newOnly, "fx-new-only", "2026-01-01T00:00:03Z");
        fills.insert(newOnly, newResult, null); orders.markConfirmed(newOnly.id());
        var both = intent(command("both", "DEMOUSDT", "1"));
        recorder.record(both, result(both, "fx-shared", "2026-01-01T00:00:01Z"));
        var all = history.history(command.actorId(), 100);
        assertThat(all).hasSize(4);
        assertThat(all).extracting(HistoryEntry::source).containsExactly("NEW", "LEGACY", "NEW", "LEGACY");
        assertThat(all).extracting(HistoryEntry::exchangeOrderId).containsExactly("fx-new-only", "fx-shared", "fx-shared", "fx-legacy-only");
        assertThat(history.history(command.actorId(), 2)).isEqualTo(all.subList(0, 2));
        assertThat(history.history("demo-other", 20)).hasSize(1);
    }

    @Test void realHttpGetsDoNotApplyRepairOrChangeAnyTable() throws Exception {
        FillRecord fill = confirmedUnapplied(command);
        List<String> before = databaseSnapshot();
        var client = HttpClient.newHttpClient();
        var order = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/demo/orders/" + fill.orderId() + "?actorId=demo-actor")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var list = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/demo/history?actorId=demo-actor&limit=20")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(order.statusCode()).isEqualTo(200);
        assertThat(order.body()).contains("\"locallyApplied\":false", "\"eventCount\":0");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(databaseSnapshot()).isEqualTo(before);
        assertThat(exchange.queryCount(command.clientOrderId())).isZero();
    }

    @Test void httpConflictingActionReturns409() throws Exception {
        service.submit(command);
        String body = "{\"actorId\":\"demo-actor\",\"actionId\":\"" + command.actionId()
                + "\",\"symbol\":\"DEMOUSDT\",\"side\":\"BUY\",\"quantity\":9}";
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/demo/orders"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(exchange.submitCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void unsupportedJsonFieldsAreRejectedBeforeSavingAnIntent() throws Exception {
        String body = "{\"actorId\":\"demo-actor\",\"actionId\":\"unsupported-field\",\"symbol\":\"DEMOUSDT\","
                + "\"side\":\"BUY\",\"quantity\":1,\"unexpected\":true}";
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/demo/orders"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(count("demo_order")).isZero();
    }

    private OrderService replacementService() {
        return new OrderService(orders, fills, applications, events, exchange,
                new FillRecorder(orders, fills, legacy, events, exchange, manager),
                new FillApplicationService(applications, manager), manager);
    }
    private OrderCommand command(String action, String symbol, String quantity) {
        return new OrderCommand("demo-actor", action, symbol, OrderCommand.Side.BUY, new BigDecimal(quantity));
    }
    private OrderIntent intent(OrderCommand c) {
        orders.insertIntent(c, c.clientOrderId(), c.fingerprint());
        return orders.findAction(c.actorId(), c.actionId());
    }
    private FillRecord confirmedUnapplied(OrderCommand c) {
        OrderIntent o = intent(c);
        orders.claim(o);
        return recorder.record(o, exchange.submit(o));
    }
    private ExchangeOrderResult result(OrderIntent o, String exchangeId, String time) {
        return new ExchangeOrderResult(true, o.clientOrderId(), o.symbol(), exchangeId, o.side(), o.quantity(), Instant.parse(time));
    }
    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
    private List<String> databaseSnapshot() {
        return List.of("demo_order", "fill", "legacy_trade", "event", "submit_claim", "local_state", "application_ledger")
                .stream().map(table -> jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]') FROM "
                        + table + " t", String.class)).toList();
    }
    private <T> List<T> race(int threads, Callable<T> task) throws Exception {
        var barrier = new CyclicBarrier(threads);
        var executor = Executors.newFixedThreadPool(threads);
        try {
            var jobs = new ArrayList<Callable<T>>();
            for (int i = 0; i < threads; i++) jobs.add(() -> { barrier.await(10, TimeUnit.SECONDS); return task.call(); });
            var results = new ArrayList<T>();
            for (var future : executor.invokeAll(jobs, 30, TimeUnit.SECONDS)) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
