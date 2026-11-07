package com.jjy.infrastructure.event.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * event_outbox Mapper
 *
 * @author jjy-base
 */
@Mapper
public interface OutboxMapper extends BaseMapper<Outbox> {

    /**
     * 认领一批待发事件（FOR UPDATE SKIP LOCKED 防止多实例争抢）
     */
    @Select("SELECT * FROM event_outbox " +
            "WHERE status = 0 AND (next_retry_at IS NULL OR next_retry_at <= NOW(3)) " +
            "ORDER BY id ASC LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<Outbox> claimBatch(@Param("limit") int limit);

    /**
     * 标记已发
     */
    @Update("UPDATE event_outbox SET status = 1, sent_at = NOW(3) WHERE id = #{id}")
    int markSent(@Param("id") Long id);

    /**
     * 标记失败（增加重试次数 + 退避时间）
     */
    @Update("UPDATE event_outbox SET status = CASE WHEN retry_count + 1 >= #{maxRetries} THEN 3 ELSE 2 END, " +
            "retry_count = retry_count + 1, " +
            "next_retry_at = DATE_ADD(NOW(3), INTERVAL #{backoffSeconds} SECOND) " +
            "WHERE id = #{id}")
    int markFailed(@Param("id") Long id, @Param("maxRetries") int maxRetries, @Param("backoffSeconds") int backoffSeconds);

    /** P8 横切-健康监控：按 status 统计积压 */
    @Select("SELECT COUNT(*) FROM event_outbox WHERE status = #{status}")
    long countByStatus(@Param("status") int status);

    /** P8 横切-健康监控：今天已发事件总数 */
    @Select("SELECT COUNT(*) FROM event_outbox WHERE status = 1 AND sent_at >= CURDATE()")
    long countSentToday();
}
