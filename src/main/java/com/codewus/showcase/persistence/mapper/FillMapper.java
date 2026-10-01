package com.codewus.showcase.persistence.mapper;

import java.util.List;
import com.codewus.showcase.exchange.ExchangeOrderResult;
import com.codewus.showcase.order.model.FillRecord;
import com.codewus.showcase.order.model.HistoryEntry;
import com.codewus.showcase.order.model.OrderIntent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FillMapper {
    int insert(@Param("order") OrderIntent order, @Param("result") ExchangeOrderResult result,
            @Param("legacyTradeId") Long legacyTradeId);
    FillRecord findByOrder(long orderId);
    List<HistoryEntry> history(String actorId);
}
