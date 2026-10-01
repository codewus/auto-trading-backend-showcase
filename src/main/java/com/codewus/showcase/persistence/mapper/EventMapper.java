package com.codewus.showcase.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EventMapper {
    int insert(@Param("fillId") long fillId, @Param("type") String type);
    boolean exists(@Param("fillId") long fillId, @Param("type") String type);
    int count(long fillId);
}
