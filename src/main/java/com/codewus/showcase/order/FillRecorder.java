package com.codewus.showcase.order;

import com.codewus.showcase.exchange.ExchangeOrderResult;
import com.codewus.showcase.exchange.FakeExchangeAdapter;
import com.codewus.showcase.order.model.FillRecord;
import com.codewus.showcase.order.model.OrderIntent;
import com.codewus.showcase.persistence.mapper.EventMapper;
import com.codewus.showcase.persistence.mapper.FillMapper;
import com.codewus.showcase.persistence.mapper.LegacyTradeMapper;
import com.codewus.showcase.persistence.mapper.OrderMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FillRecorder {
    private final OrderMapper orders;
    private final FillMapper fills;
    private final LegacyTradeMapper legacy;
    private final EventMapper events;
    private final FakeExchangeAdapter faults;
    private final TransactionTemplate transaction;

    public FillRecorder(OrderMapper orders, FillMapper fills, LegacyTradeMapper legacy,
            EventMapper events, FakeExchangeAdapter faults, PlatformTransactionManager manager) {
        this.orders = orders;
        this.fills = fills;
        this.legacy = legacy;
        this.events = events;
        this.faults = faults;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public FillRecord record(OrderIntent order, ExchangeOrderResult result) {
        validate(order, result);
        return transaction.execute(status -> {
            // Compatibility write is atomic with the new aggregate Fill. No historical backfill.
            legacy.insert(order, result);
            Long legacyId = legacy.findId(order.actorId(), order.symbol(), result.exchangeOrderId());
            fills.insert(order, result, legacyId);
            FillRecord saved = fills.findByOrder(order.id());
            if (saved == null || !saved.exchangeOrderId().equals(result.exchangeOrderId())
                    || saved.quantity().compareTo(result.quantity()) != 0) {
                throw new IllegalStateException("confirmed result conflicts with stored aggregate fill");
            }
            orders.markConfirmed(order.id());
            return saved;
        });
    }

    public boolean repairEvents(OrderIntent order, FillRecord fill) {
        boolean complete = true;
        for (String type : new String[] { "ORDER_CONFIRMED", "FILL_RECORDED" }) {
            if (events.exists(fill.id(), type)) continue;
            try {
                transaction.executeWithoutResult(status -> {
                    faults.beforeEvent(order.clientOrderId());
                    events.insert(fill.id(), type);
                });
            } catch (DataAccessException | FakeExchangeAdapter.TransientEventException exception) {
                complete = false; // Fill remains committed; response flags and recovery expose the gap.
            }
        }
        return complete;
    }

    private static void validate(OrderIntent order, ExchangeOrderResult result) {
        if (!result.confirmed() || !order.clientOrderId().equals(result.clientOrderId())
                || !order.symbol().equals(result.symbol()) || order.side() != result.side()
                || result.quantity() == null || order.quantity().compareTo(result.quantity()) != 0
                || result.exchangeOrderId() == null || result.filledAt() == null) {
            throw new IllegalArgumentException("result must match the requested aggregate fill");
        }
    }
}
