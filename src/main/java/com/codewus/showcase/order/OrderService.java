package com.codewus.showcase.order;

import com.codewus.showcase.exchange.ExchangeAdapter;
import com.codewus.showcase.exchange.ExchangeOrderResult;
import com.codewus.showcase.exchange.FakeExchangeAdapter;
import com.codewus.showcase.order.model.ActionConflictException;
import com.codewus.showcase.order.model.FillRecord;
import com.codewus.showcase.order.model.OrderCommand;
import com.codewus.showcase.order.model.OrderIntent;
import com.codewus.showcase.order.model.OrderView;
import com.codewus.showcase.persistence.mapper.ApplicationMapper;
import com.codewus.showcase.persistence.mapper.EventMapper;
import com.codewus.showcase.persistence.mapper.FillMapper;
import com.codewus.showcase.persistence.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {
    private final OrderMapper orders;
    private final FillMapper fills;
    private final ApplicationMapper applications;
    private final EventMapper events;
    private final ExchangeAdapter exchange;
    private final FillRecorder recorder;
    private final FillApplicationService application;
    private final TransactionTemplate transaction;

    public OrderService(OrderMapper orders, FillMapper fills, ApplicationMapper applications,
            EventMapper events, ExchangeAdapter exchange, FillRecorder recorder,
            FillApplicationService application, PlatformTransactionManager manager) {
        this.orders = orders;
        this.fills = fills;
        this.applications = applications;
        this.events = events;
        this.exchange = exchange;
        this.recorder = recorder;
        this.application = application;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public OrderView submit(OrderCommand command) {
        OrderIntent intent = transaction.execute(status -> {
            orders.insertIntent(command, command.clientOrderId(), command.fingerprint());
            OrderIntent saved = orders.findAction(command.actorId(), command.actionId());
            if (saved == null) throw new IllegalStateException("clientOrderId identity conflict");
            if (!saved.fingerprint().equals(command.fingerprint())) throw new ActionConflictException();
            return saved;
        });
        if (fills.findByOrder(intent.id()) != null) return completeLocal(intent);
        boolean owner = Boolean.TRUE.equals(transaction.execute(status -> orders.claim(intent) == 1));
        // A committed claim is permanent. No lease expiry or automatic reclaim/resubmit.
        ExchangeOrderResult result;
        if (owner) {
            try {
                result = exchange.submit(intent);
            } catch (FakeExchangeAdapter.ResponseLostException exception) {
                result = exchange.query(intent);
            }
        } else {
            result = exchange.query(intent);
        }
        acceptResult(intent, result);
        return completeLocal(intent);
    }

    public void reconcile(OrderIntent intent) {
        if (fills.findByOrder(intent.id()) == null) acceptResult(intent, exchange.query(intent));
        completeLocal(intent);
    }

    private void acceptResult(OrderIntent intent, ExchangeOrderResult result) {
        if (result.confirmed()) recorder.record(intent, result);
        else transaction.executeWithoutResult(status -> orders.markUncertain(intent.id()));
    }

    private OrderView completeLocal(OrderIntent intent) {
        FillRecord fill = fills.findByOrder(intent.id());
        if (fill != null) {
            application.apply(fill);
            recorder.repairEvents(intent, fill);
        }
        return get(intent.actorId(), intent.id());
    }

    /** Pure read: GET must never query the exchange, apply a Fill or repair an Event. */
    public OrderView get(String actorId, long id) {
        OrderCommand.requireIdentifier(actorId, "actorId");
        OrderIntent intent = orders.find(actorId, id);
        if (intent == null) throw new java.util.NoSuchElementException("order not found");
        FillRecord fill = fills.findByOrder(id);
        return new OrderView(intent, fill, fill != null && applications.isApplied(fill.id()),
                fill == null ? 0 : events.count(fill.id()));
    }
}
