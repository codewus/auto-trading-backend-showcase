package com.codewus.showcase.persistence.mapper;

import java.util.List;
import com.codewus.showcase.order.model.OrderCommand;
import com.codewus.showcase.order.model.OrderIntent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {
    int insertIntent(@Param("command") OrderCommand command, @Param("clientOrderId") String clientOrderId,
            @Param("fingerprint") String fingerprint);
    OrderIntent findAction(@Param("actorId") String actorId, @Param("actionId") String actionId);
    OrderIntent find(@Param("actorId") String actorId, @Param("id") long id);
    List<OrderIntent> forActor(String actorId);
    int claim(OrderIntent intent);
    int markUncertain(long id);
    int markConfirmed(long id);
}
