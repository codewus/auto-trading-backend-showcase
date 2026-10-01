package com.codewus.showcase.exchange;

import java.math.BigDecimal;
import java.time.Instant;
import com.codewus.showcase.order.model.OrderCommand;

public record ExchangeOrderResult(boolean confirmed, String clientOrderId, String symbol,
        String exchangeOrderId, OrderCommand.Side side, BigDecimal quantity, Instant filledAt) {
    public static ExchangeOrderResult unknown() {
        return new ExchangeOrderResult(false, null, null, null, null, null, null);
    }
}
