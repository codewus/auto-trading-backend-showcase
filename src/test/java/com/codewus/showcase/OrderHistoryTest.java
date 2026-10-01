package com.codewus.showcase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.codewus.showcase.order.OrderHistoryService;
import com.codewus.showcase.order.model.HistoryEntry;
import com.codewus.showcase.order.model.OrderCommand;
import com.codewus.showcase.persistence.mapper.FillMapper;
import com.codewus.showcase.persistence.mapper.LegacyTradeMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderHistoryTest {
    final FillMapper fills = mock(FillMapper.class);
    final LegacyTradeMapper legacy = mock(LegacyTradeMapper.class);
    final OrderHistoryService service = new OrderHistoryService(fills, legacy);

    @Test void newOnlyLegacyOnlyAndBothAppearOnceAfterGlobalSortAndLimit() {
        when(fills.history("demo")).thenReturn(List.of(entry("NEW", 10L, null, "DEMOUSDT", "new", 3),
                entry("NEW", 11L, 2L, "DEMOUSDT", "both", 2)));
        when(legacy.history("demo")).thenReturn(List.of(entry("LEGACY", null, 1L, "DEMOUSDT", "old", 1),
                entry("LEGACY", null, 2L, "DEMOUSDT", "both", 4)));
        var all = service.history("demo", 100);
        assertThat(all).extracting(HistoryEntry::exchangeOrderId).containsExactly("new", "both", "old");
        assertThat(all).extracting(HistoryEntry::source).containsExactly("NEW", "NEW", "LEGACY");
        assertThat(service.history("demo", 2)).isEqualTo(all.subList(0, 2));
    }

    @Test void exchangeIdAndLegacyLinkAreScopedByActorAndSymbol() {
        when(fills.history("demo")).thenReturn(List.of(entry("NEW", 10L, 2L, "DEMOUSDT", "shared", 3)));
        when(legacy.history("demo")).thenReturn(List.of(entry("LEGACY", null, 3L, "OTHERUSDT", "shared", 2)));
        assertThat(service.history("demo", 20)).hasSize(2);
    }

    @Test void sameTimestampUsesStableSourceAndNumericIdTieBreakers() {
        when(fills.history("demo")).thenReturn(List.of(entry("NEW", 10L, null, "DEMOUSDT", "a", 1),
                entry("NEW", 12L, null, "DEMOUSDT", "b", 1)));
        when(legacy.history("demo")).thenReturn(List.of(entry("LEGACY", null, 30L, "DEMOUSDT", "c", 1)));
        assertThat(service.history("demo", 3)).extracting(HistoryEntry::exchangeOrderId).containsExactly("b", "a", "c");
    }

    @Test void invalidLimitIsRejectedBeforeAnyRead() {
        assertThatThrownBy(() -> service.history("demo", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.history("demo", 101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(fills, legacy);
    }

    private HistoryEntry entry(String source, Long orderId, Long legacyId, String symbol, String exchangeId, int seconds) {
        return new HistoryEntry(source, orderId, legacyId, "demo", symbol, exchangeId, OrderCommand.Side.BUY,
                BigDecimal.ONE, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds));
    }
}
