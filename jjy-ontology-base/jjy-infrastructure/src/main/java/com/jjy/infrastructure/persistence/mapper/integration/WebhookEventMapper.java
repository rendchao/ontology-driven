package com.jjy.infrastructure.persistence.mapper.integration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjy.infrastructure.persistence.po.integration.WebhookEventPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * t_webhook_event Mapper
 */
@Mapper
public interface WebhookEventMapper extends BaseMapper<WebhookEventPO> {

    @Select("SELECT * FROM t_webhook_event WHERE event_uid = #{eventUid} AND flag = 0")
    WebhookEventPO findByEventUid(@Param("eventUid") String eventUid);

    /** 待重试的事件：status=2 且 next_retry_at < now */
    @Select("SELECT * FROM t_webhook_event WHERE status = 2 AND next_retry_at IS NOT NULL AND next_retry_at < #{now} AND retry_count < 3 AND flag = 0 ORDER BY next_retry_at ASC LIMIT 50")
    List<WebhookEventPO> findRetryable(@Param("now") LocalDateTime now);

    /** P8 横切-健康监控：1 分钟内 webhook 事件数 */
    @Select("SELECT COUNT(*) FROM t_webhook_event WHERE received_at > DATE_SUB(NOW(), INTERVAL 1 MINUTE) AND flag = 0")
    long countLast1Min();

    /** P8 横切-健康监控：今天 webhook 事件数 */
    @Select("SELECT COUNT(*) FROM t_webhook_event WHERE received_at >= CURDATE() AND flag = 0")
    long countToday();
}
