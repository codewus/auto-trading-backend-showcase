package com.codewus.showcase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.codewus.showcase.exchange.ExchangeOrderResult;
import com.codewus.showcase.exchange.FakeExchangeAdapter;
import com.codewus.showcase.order.FillApplicationService;
import com.codewus.showcase.order.FillRecorder;
import com.codewus.showcase.order.OrderService;
import com.codewus.showcase.order.model.FillRecord;
import com.codewus.showcase.order.model.OrderCommand;
import com.codewus.showcase.order.model.OrderIntent;
import com.codewus.showcase.persistence.mapper.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit-only mapper doubles. SQL and rollback guarantees are tested against PostgreSQL. */
class UnitFixture {
    final OrderMapper orders = mock(OrderMapper.class);
    final FillMapper fills = mock(FillMapper.class);
    final LegacyTradeMapper legacy = mock(LegacyTradeMapper.class);
    final EventMapper events = mock(EventMapper.class);
    final ApplicationMapper applications = mock(ApplicationMapper.class);
    final FakeExchangeAdapter exchange = new FakeExchangeAdapter();
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final Map<Long, OrderIntent> intents = new HashMap<>();
    final Map<Long, FillRecord> storedFills = new HashMap<>();
    final Set<Long> claims = new HashSet<>();
    final Set<Long> applied = new HashSet<>();
    final Set<String> storedEvents = new HashSet<>();
    BigDecimal signedQuantity = BigDecimal.ZERO;
    int stateChanges;
    final FillRecorder recorder;
    final FillApplicationService application;

    UnitFixture() {
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        when(orders.insertIntent(any(), anyString(), anyString())).thenAnswer(call -> {
            OrderCommand c = call.getArgument(0);
            boolean exists = intents.values().stream().anyMatch(o -> o.actorId().equals(c.actorId()) && o.actionId().equals(c.actionId()));
            if (exists) return 0;
            long id = intents.size() + 1L;
            intents.put(id, new OrderIntent(id, c.actorId(), c.actionId(), c.clientOrderId(), c.fingerprint(),
                    c.symbol(), c.side(), c.quantity(), OrderIntent.Status.INTENDED, Instant.now()));
            return 1;
        });
        when(orders.findAction(anyString(), anyString())).thenAnswer(call -> intents.values().stream()
                .filter(o -> o.actorId().equals(call.getArgument(0)) && o.actionId().equals(call.getArgument(1))).findFirst().orElse(null));
        when(orders.find(anyString(), anyLong())).thenAnswer(call -> {
            OrderIntent o = intents.get(call.<Long>getArgument(1));
            return o != null && o.actorId().equals(call.getArgument(0)) ? o : null;
        });
        when(orders.forActor(anyString())).thenAnswer(call -> new ArrayList<>(intents.values().stream()
                .filter(o -> o.actorId().equals(call.getArgument(0))).toList()));
        when(orders.claim(any())).thenAnswer(call -> claims.add(call.<OrderIntent>getArgument(0).id()) ? 1 : 0);
        when(orders.markConfirmed(anyLong())).thenAnswer(call -> mark(call.getArgument(0), OrderIntent.Status.CONFIRMED));
        when(orders.markUncertain(anyLong())).thenAnswer(call -> mark(call.getArgument(0), OrderIntent.Status.UNCERTAIN));
        when(fills.insert(any(), any(), nullable(Long.class))).thenAnswer(call -> {
            OrderIntent o = call.getArgument(0); ExchangeOrderResult r = call.getArgument(1);
            return storedFills.putIfAbsent(o.id(), new FillRecord(o.id(), o.id(), o.actorId(), o.symbol(),
                    r.exchangeOrderId(), r.side(), r.quantity(), call.getArgument(2), r.filledAt())) == null ? 1 : 0;
        });
        when(fills.findByOrder(anyLong())).thenAnswer(call -> storedFills.get(call.<Long>getArgument(0)));
        when(legacy.findId(anyString(), anyString(), anyString())).thenReturn(1L);
        when(applications.isApplied(anyLong())).thenAnswer(call -> applied.contains(call.<Long>getArgument(0)));
        when(applications.updateState(any())).thenAnswer(call -> {
            FillRecord f = call.getArgument(0);
            signedQuantity = signedQuantity.add(f.side() == OrderCommand.Side.BUY ? f.quantity() : f.quantity().negate());
            stateChanges++; return 1;
        });
        when(applications.markApplied(anyLong())).thenAnswer(call -> applied.add(call.<Long>getArgument(0)) ? 1 : 0);
        when(events.exists(anyLong(), anyString())).thenAnswer(call -> storedEvents.contains(call.getArgument(0) + ":" + call.getArgument(1)));
        when(events.insert(anyLong(), anyString())).thenAnswer(call -> storedEvents.add(call.getArgument(0) + ":" + call.getArgument(1)) ? 1 : 0);
        when(events.count(anyLong())).thenAnswer(call -> (int) storedEvents.stream().filter(e -> e.startsWith(call.getArgument(0) + ":")).count());
        recorder = new FillRecorder(orders, fills, legacy, events, exchange, manager);
        application = new FillApplicationService(applications, manager);
    }

    OrderService service() { return new OrderService(orders, fills, applications, events, exchange, recorder, application, manager); }

    private int mark(long id, OrderIntent.Status status) {
        OrderIntent o = intents.get(id);
        if (o.status() == OrderIntent.Status.CONFIRMED && status == OrderIntent.Status.UNCERTAIN) return 0;
        intents.put(id, new OrderIntent(o.id(), o.actorId(), o.actionId(), o.clientOrderId(), o.fingerprint(),
                o.symbol(), o.side(), o.quantity(), status, o.createdAt()));
        return 1;
    }
}
