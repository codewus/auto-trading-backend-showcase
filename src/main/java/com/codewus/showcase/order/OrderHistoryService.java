package com.codewus.showcase.order;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.codewus.showcase.order.model.HistoryEntry;
import com.codewus.showcase.order.model.OrderCommand;
import com.codewus.showcase.persistence.mapper.FillMapper;
import com.codewus.showcase.persistence.mapper.LegacyTradeMapper;
import org.springframework.stereotype.Service;

@Service
public class OrderHistoryService {
    private record ExchangeKey(String actorId, String symbol, String exchangeOrderId) { }
    private record LegacyKey(String actorId, String symbol, long legacyTradeId) { }
    private final FillMapper fills;
    private final LegacyTradeMapper legacy;

    public OrderHistoryService(FillMapper fills, LegacyTradeMapper legacy) {
        this.fills = fills;
        this.legacy = legacy;
    }

    /** Small demo data set: merge first, globally sort, then limit. No write/repair. */
    public List<HistoryEntry> history(String actorId, int limit) {
        OrderCommand.requireIdentifier(actorId, "actorId");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1-100");
        List<HistoryEntry> combined = new ArrayList<>(fills.history(actorId));
        Set<ExchangeKey> represented = new HashSet<>();
        Set<LegacyKey> representedLegacy = new HashSet<>();
        for (HistoryEntry entry : combined) {
            represented.add(new ExchangeKey(entry.actorId(), entry.symbol(), entry.exchangeOrderId()));
            if (entry.legacyTradeId() != null) {
                representedLegacy.add(new LegacyKey(entry.actorId(), entry.symbol(), entry.legacyTradeId()));
            }
        }
        for (HistoryEntry entry : legacy.history(actorId)) {
            if (!represented.contains(new ExchangeKey(entry.actorId(), entry.symbol(), entry.exchangeOrderId()))
                    && !representedLegacy.contains(new LegacyKey(entry.actorId(), entry.symbol(), entry.legacyTradeId()))) {
                combined.add(entry);
            }
        }
        combined.sort(Comparator.comparing(HistoryEntry::occurredAt).reversed()
                .thenComparing(entry -> "NEW".equals(entry.source()) ? 0 : 1)
                .thenComparing(Comparator.comparingLong((HistoryEntry entry) ->
                        entry.orderId() != null ? entry.orderId() : entry.legacyTradeId()).reversed()));
        return List.copyOf(combined.subList(0, Math.min(limit, combined.size())));
    }
}
