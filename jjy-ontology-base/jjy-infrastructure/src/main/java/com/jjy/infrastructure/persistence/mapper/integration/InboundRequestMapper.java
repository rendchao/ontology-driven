package com.jjy.infrastructure.persistence.mapper.integration;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjy.infrastructure.persistence.po.integration.InboundRequestPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * t_inbound_request Mapper
 */
@Mapper
public interface InboundRequestMapper extends BaseMapper<InboundRequestPO> {

    @Select("SELECT * FROM t_inbound_request WHERE request_uid = #{requestUid} AND flag = 0")
    InboundRequestPO findByRequestUid(@Param("requestUid") String requestUid);

    /**
     * P8 横切-限流监控：最近 1 分钟每个 API Key 的入站调用次数
     */
    @Select("SELECT api_key AS apiKey, COUNT(*) AS cnt " +
            "FROM t_inbound_request " +
            "WHERE created_at > DATE_SUB(NOW(), INTERVAL 1 MINUTE) " +
            "GROUP BY api_key " +
            "ORDER BY cnt DESC")
    List<Map<String, Object>> countByApiKeyLast1Min();

    /** P8 横切-健康监控：今天入站调用总数 */
    @Select("SELECT COUNT(*) FROM t_inbound_request WHERE created_at >= CURDATE() AND flag = 0")
    long countToday();
}
