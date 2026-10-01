package com.codewus.showcase.order.model;

import java.math.BigDecimal;
import java.time.Instant;

/** One confirmed aggregate result, not every partial execution at an exchange. */
public record FillRecord(long id, long orderId, String actorId, String symbol,
        String exchangeOrderId, OrderCommand.Side side, BigDecimal quantity,
        Long legacyTradeId, Instant filledAt) { }
