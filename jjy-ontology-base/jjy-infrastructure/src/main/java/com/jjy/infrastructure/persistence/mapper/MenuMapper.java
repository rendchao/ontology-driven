package com.jjy.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjy.infrastructure.persistence.po.MenuPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MenuMapper extends BaseMapper<MenuPO> {
}
