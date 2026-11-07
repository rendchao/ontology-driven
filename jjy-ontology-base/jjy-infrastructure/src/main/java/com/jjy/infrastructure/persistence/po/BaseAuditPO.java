package com.jjy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 所有业务表的 5 默认字段基类
 * <p>created_by / created_at / updated_by / updated_at / flag</p>
 * <p>由 MyBatis-Plus MetaObjectHandler 自动填充</p>
 *
 * @author jjy-base
 */
@Data
public abstract class BaseAuditPO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableField(value = "created_by", fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_by", fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 业务 flag: 0=有效 1=无效 (MyBatis-Plus 逻辑删除)。
     * <p>加了 @TableLogic 后:
     * <ul>
     *   <li>所有查询自动追加 WHERE flag=0</li>
     *   <li>deleteById 变成 UPDATE flag=1</li>
     * </ul>
     * insert 时由 {@link com.jjy.infrastructure.config.MybatisPlusMetaObjectHandler}
     * 显式填 0（@TableLogic 不会自动填）。</p>
     */
    @TableLogic
    @TableField(value = "flag")
    private Integer flag;
}

