package com.codewus.showcase.order.model;

import java.math.BigDecimal;

/** Synthetic signed quantity and application count; not an account balance. */
public record LocalState(String actorId, String symbol, BigDecimal appliedQuantity, long appliedFillCount) { }
