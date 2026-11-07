package com.jjy.infrastructure.persistence.mapper.security;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjy.infrastructure.persistence.po.security.ApiCredentialPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * t_api_credential Mapper
 *
 * @author jjy-base
 */
@Mapper
public interface ApiCredentialMapper extends BaseMapper<ApiCredentialPO> {

    @Select("SELECT * FROM t_api_credential WHERE api_key = #{apiKey} AND flag = 0 LIMIT 1")
    ApiCredentialPO findByApiKey(@Param("apiKey") String apiKey);
}
