package com.codewus.showcase.exchange;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.codewus.showcase.order.model.OrderCommand;
import com.codewus.showcase.order.model.OrderIntent;
import org.springframework.stereotype.Component;

/** Shared in-memory fixture state survives replacement of application service instances. */
@Component
public class FakeExchangeAdapter implements ExchangeAdapter {
    public enum Scenario { NORMAL, RESPONSE_LOST, UNKNOWN, REPEATED_RESULT, EVENT_FAILURE }
    public static class ResponseLostException extends RuntimeException { }
    public static class TransientEventException extends RuntimeException { }

    private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();
    private final Map<String, ExchangeOrderResult> results = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> submits = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> queries = new ConcurrentHashMap<>();
    private final Set<String> eventFailures = ConcurrentHashMap.newKeySet();

    public void selectScenario(String actorId, String actionId, Scenario scenario) {
        OrderCommand.requireIdentifier(actorId, "actorId");
        OrderCommand.requireIdentifier(actionId, "actionId");
        if (scenario == null) throw new IllegalArgumentException("scenario required");
        scenarios.put(actorId + "|" + actionId, scenario);
    }

    @Override
    public ExchangeOrderResult submit(OrderIntent intent) {
        submits.computeIfAbsent(intent.clientOrderId(), ignored -> new AtomicInteger()).incrementAndGet();
        Scenario scenario = scenarios.getOrDefault(intent.actorId() + "|" + intent.actionId(), Scenario.NORMAL);
        if (scenario == Scenario.UNKNOWN) return ExchangeOrderResult.unknown();
        ExchangeOrderResult result = results.computeIfAbsent(intent.clientOrderId(), ignored ->
                new ExchangeOrderResult(true, intent.clientOrderId(), intent.symbol(),
                        "fx-" + intent.clientOrderId(), intent.side(), intent.quantity(), Instant.now()));
        if (scenario == Scenario.EVENT_FAILURE) eventFailures.add(intent.clientOrderId());
        if (scenario == Scenario.RESPONSE_LOST) throw new ResponseLostException();
        return result;
    }

    @Override
    public ExchangeOrderResult query(OrderIntent intent) {
        queries.computeIfAbsent(intent.clientOrderId(), ignored -> new AtomicInteger()).incrementAndGet();
        return results.getOrDefault(intent.clientOrderId(), ExchangeOrderResult.unknown());
    }

    public void beforeEvent(String clientOrderId) {
        if (eventFailures.remove(clientOrderId)) throw new TransientEventException();
    }

    public int submitCount(String clientOrderId) {
        return submits.getOrDefault(clientOrderId, new AtomicInteger()).get();
    }

    public int queryCount(String clientOrderId) {
        return queries.getOrDefault(clientOrderId, new AtomicInteger()).get();
    }
}
