package com.codewus.showcase.order;

import java.util.ArrayList;
import java.util.List;
import com.codewus.showcase.order.model.OrderCommand;
import com.codewus.showcase.order.model.OrderIntent;
import com.codewus.showcase.order.model.OrderView;
import com.codewus.showcase.persistence.mapper.OrderMapper;
import org.springframework.stereotype.Service;

@Service
public class RecoveryService {
    private final OrderMapper orders;
    private final OrderService service;

    public RecoveryService(OrderMapper orders, OrderService service) {
        this.orders = orders;
        this.service = service;
    }

    /** Explicit POST only. In particular, recovery never acquires a new submit claim. */
    public List<OrderView> recover(String actorId) {
        OrderCommand.requireIdentifier(actorId, "actorId");
        List<OrderView> results = new ArrayList<>();
        for (OrderIntent intent : orders.forActor(actorId)) {
            service.reconcile(intent);
            results.add(service.get(actorId, intent.id()));
        }
        return List.copyOf(results);
    }
}
