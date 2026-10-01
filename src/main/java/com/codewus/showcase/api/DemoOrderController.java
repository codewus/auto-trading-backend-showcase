package com.codewus.showcase.api;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import com.codewus.showcase.api.dto.SubmitOrderRequest;
import com.codewus.showcase.exchange.FakeExchangeAdapter;
import com.codewus.showcase.order.OrderHistoryService;
import com.codewus.showcase.order.OrderService;
import com.codewus.showcase.order.RecoveryService;
import com.codewus.showcase.order.model.ActionConflictException;
import com.codewus.showcase.order.model.HistoryEntry;
import com.codewus.showcase.order.model.OrderView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo")
public class DemoOrderController {
    public record ScenarioRequest(String actorId, String actionId, FakeExchangeAdapter.Scenario scenario) { }
    private final OrderService orders;
    private final OrderHistoryService history;
    private final RecoveryService recovery;
    private final FakeExchangeAdapter fake;

    public DemoOrderController(OrderService orders, OrderHistoryService history,
            RecoveryService recovery, FakeExchangeAdapter fake) {
        this.orders = orders;
        this.history = history;
        this.recovery = recovery;
        this.fake = fake;
    }

    @PostMapping("/orders")
    public OrderView submit(@Valid @RequestBody SubmitOrderRequest request) {
        return orders.submit(request.command());
    }

    @GetMapping("/orders/{id}")
    public OrderView get(@PathVariable long id, @RequestParam String actorId) { return orders.get(actorId, id); }

    @GetMapping("/history")
    public List<HistoryEntry> history(@RequestParam String actorId, @RequestParam(defaultValue = "20") int limit) {
        return history.history(actorId, limit);
    }

    @PostMapping("/recovery")
    public List<OrderView> recover(@RequestParam String actorId) { return recovery.recover(actorId); }

    @PostMapping("/scenarios")
    public Map<String, String> scenario(@RequestBody ScenarioRequest request) {
        fake.selectScenario(request.actorId(), request.actionId(), request.scenario());
        return Map.of("scenario", request.scenario().name());
    }

    @ExceptionHandler(ActionConflictException.class)
    public ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "ACTION_CONFLICT"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "ORDER_NOT_FOUND"));
    }
}
