package com.codewus.showcase.order.model;

import java.math.BigDecimal;
import java.time.Instant;

public record HistoryEntry(String source, Long orderId, Long legacyTradeId,
        String actorId, String symbol, String exchangeOrderId, OrderCommand.Side side,
        BigDecimal quantity, Instant occurredAt) { }
