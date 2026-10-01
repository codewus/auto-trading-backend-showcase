package com.codewus.showcase.order.model;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderIntent(long id, String actorId, String actionId, String clientOrderId,
        String fingerprint, String symbol, OrderCommand.Side side, BigDecimal quantity,
        Status status, Instant createdAt) {
    public enum Status { INTENDED, UNCERTAIN, CONFIRMED }

    public OrderCommand command() {
        return new OrderCommand(actorId, actionId, symbol, side, quantity);
    }
}
