package com.codewus.showcase.persistence.mapper;

import com.codewus.showcase.order.model.FillRecord;
import com.codewus.showcase.order.model.LocalState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApplicationMapper {
    int ensureState(FillRecord fill);
    LocalState lockState(FillRecord fill);
    boolean isApplied(long fillId);
    int updateState(FillRecord fill);
    int markApplied(long fillId);
    LocalState state(@Param("actorId") String actorId, @Param("symbol") String symbol);
}
