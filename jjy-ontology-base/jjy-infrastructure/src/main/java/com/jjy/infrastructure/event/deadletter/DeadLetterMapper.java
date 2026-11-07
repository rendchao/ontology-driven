package com.jjy.infrastructure.event.deadletter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeadLetterMapper extends BaseMapper<DeadLetter> {

    @Insert("INSERT INTO event_dead_letter (event_uid, handler, payload, failure_msg, resolved_flag, created_at) " +
            "VALUES (#{eventUid}, #{handler}, #{payload}, #{failureMsg}, 0, NOW(3))")
    int insertDeadLetter(@Param("eventUid") String eventUid,
                         @Param("handler") String handler,
                         @Param("payload") String payload,
                         @Param("failureMsg") String failureMsg);
}
