package com.codewus.showcase.persistence.mapper;

import java.util.List;
import com.codewus.showcase.exchange.ExchangeOrderResult;
import com.codewus.showcase.order.model.HistoryEntry;
import com.codewus.showcase.order.model.OrderIntent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LegacyTradeMapper {
    int insert(@Param("order") OrderIntent order, @Param("result") ExchangeOrderResult result);
    Long findId(@Param("actorId") String actorId, @Param("symbol") String symbol,
            @Param("exchangeOrderId") String exchangeOrderId);
    List<HistoryEntry> history(String actorId);
}
