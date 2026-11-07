package com.jjy.infrastructure.event.idempotent;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EventHandlerLogMapper extends BaseMapper<EventHandlerLog> {

    /**
     * 原子占位：UNIQUE(event_uid, handler) 冲突即已处理
     */
    @Insert("INSERT INTO event_handler_log (event_uid, handler, processed_at, result, error_msg) " +
            "VALUES (#{eventUid}, #{handler}, NOW(3), #{result}, #{errorMsg})")
    int insertLog(@Param("eventUid") String eventUid,
                  @Param("handler") String handler,
                  @Param("result") int result,
                  @Param("errorMsg") String errorMsg);

    /**
     * 标记失败
     */
    @Update("UPDATE event_handler_log SET result = 1, error_msg = #{errorMsg} WHERE event_uid = #{eventUid} AND handler = #{handler}")
    int markFailed(@Param("eventUid") String eventUid,
                   @Param("handler") String handler,
                   @Param("errorMsg") String errorMsg);
}
