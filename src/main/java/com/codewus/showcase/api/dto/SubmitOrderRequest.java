package com.codewus.showcase.api.dto;

import java.math.BigDecimal;
import com.codewus.showcase.order.model.OrderCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SubmitOrderRequest(
        @NotNull @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String actorId,
        @NotNull @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String actionId,
        @NotNull @Pattern(regexp = "[A-Z][A-Z0-9]{1,19}") String symbol,
        @NotNull OrderCommand.Side side, @NotNull BigDecimal quantity) {
    public OrderCommand command() { return new OrderCommand(actorId, actionId, symbol, side, quantity); }
}
