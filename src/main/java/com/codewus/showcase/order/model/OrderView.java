package com.codewus.showcase.order.model;

public record OrderView(OrderIntent order, FillRecord fill, boolean locallyApplied, int eventCount) { }
