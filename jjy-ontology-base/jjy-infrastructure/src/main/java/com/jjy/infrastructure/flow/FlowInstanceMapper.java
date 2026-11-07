package com.jjy.infrastructure.flow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * flow_instance + flow_activity_instance Mapper
 *
 * <p>状态变更统一用 {@link #compareAndSetStatus} 走乐观锁，避免并发覆盖。</p>
 */
@Mapper
public interface FlowInstanceMapper extends BaseMapper<FlowInstancePO> {

    /** 启动恢复：找所有 RUNNING 实例（P5 没有 WAITING，但状态枚举里预留了） */
    @Select("SELECT * FROM flow_instance WHERE status IN ('RUNNING','WAITING')")
    List<FlowInstancePO> findActive();

    /**
     * 原子状态更新：仅当当前状态匹配时才更新
     */
    @Update("UPDATE flow_instance SET status = #{newStatus}, current_activity = #{currentActivity}, " +
            "context = #{context}, error_msg = #{errorMsg}, updated_at = NOW(3) " +
            "WHERE id = #{id} AND status = #{oldStatus}")
    int compareAndSetStatus(@Param("id") String id,
                            @Param("oldStatus") String oldStatus,
                            @Param("newStatus") String newStatus,
                            @Param("currentActivity") String currentActivity,
                            @Param("context") String context,
                            @Param("errorMsg") String errorMsg);
}
