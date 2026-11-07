package com.jjy.infrastructure.scenario;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * scenario_instance Mapper
 *
 * @author jjy-base
 */
@Mapper
public interface ScenarioInstanceMapper extends BaseMapper<ScenarioInstancePO> {

    /**
     * 启动恢复：找所有 RUNNING + WAITING 实例
     */
    @Select("SELECT * FROM scenario_instance WHERE status IN ('RUNNING','WAITING')")
    List<ScenarioInstancePO> findActive();

    /**
     * 找等指定 event 的所有 WAITING 实例（EventBridge 用）
     */
    @Select("SELECT * FROM scenario_instance WHERE status = 'WAITING' AND current_step IN " +
            "(SELECT step_id FROM (SELECT current_step AS step_id FROM scenario_instance WHERE status = 'WAITING') t) " +
            "AND id IN (SELECT id FROM scenario_instance WHERE status = 'WAITING') " +
            "AND JSON_EXTRACT(context, CONCAT('$.waitingEventRef')) = #{eventRef}")
    List<ScenarioInstancePO> findWaitingByEventRef(@Param("eventRef") String eventRef);

    /**
     * 找所有 WAITING 且 context.waitingDeadline 已写入的候选实例。
     *
     * <p>注意：超时判断（deadline 与 NOW 的比较）不在 SQL 里做。原因：
     * <ol>
     *   <li>{@code JSON_EXTRACT} 对字符串值会带引号返回，和 {@code NOW(3)} 比较时类型/精度都不一致，
     *       已实测会把 7 天后才到期的实例错误标为超时；</li>
     *   <li>Java 端存的是 {@link java.time.LocalDateTime#toString()} 的 ISO-8601（含 9 位纳秒），
     *       SQL 端用 {@code STR_TO_DATE(LEFT(..., 23), '%Y-%m-%dT%H:%i:%s.%f')} 截断解析也有
     *       格式脆弱性问题（万一 Java 端改成不带纳秒就会全错）。</li>
     * </ol>
     * 因此本方法只做"有 deadline 候选"初筛，真正的时间比较由
     * {@code ScenarioTimeoutScanner} 在 Java 里用 {@code LocalDateTime.parse} 完成。
     */
    @Select("SELECT * FROM scenario_instance WHERE status = 'WAITING' " +
            "AND JSON_UNQUOTE(JSON_EXTRACT(context, '$.waitingDeadline')) IS NOT NULL")
    List<ScenarioInstancePO> findTimedOut();

    /**
     * 原子状态更新：仅当当前状态匹配时才更新
     */
    @Update("UPDATE scenario_instance SET status = #{newStatus}, current_step = #{currentStep}, " +
            "context = #{context}, error_msg = #{errorMsg}, updated_at = NOW(3) " +
            "WHERE id = #{id} AND status = #{oldStatus}")
    int compareAndSetStatus(@Param("id") String id,
                            @Param("oldStatus") String oldStatus,
                            @Param("newStatus") String newStatus,
                            @Param("currentStep") String currentStep,
                            @Param("context") String context,
                            @Param("errorMsg") String errorMsg);

    /** P8 横切-健康监控：按状态统计实例数 */
    @Select("SELECT COUNT(*) FROM scenario_instance WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}
