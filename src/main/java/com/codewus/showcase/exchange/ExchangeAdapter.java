package com.codewus.showcase.exchange;

import com.codewus.showcase.order.model.OrderIntent;

public interface ExchangeAdapter {
    ExchangeOrderResult submit(OrderIntent intent);
    ExchangeOrderResult query(OrderIntent intent);
}
