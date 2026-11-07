package com.jjy.infrastructure.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.jjy.infrastructure.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充 created_by / created_at / updated_by / updated_at
 *
 * @author jjy-base
 */
@Slf4j
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String userId = currentUserIdOrSystem();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "createdBy", String.class, userId);
        this.strictInsertFill(metaObject, "updatedBy", String.class, userId);
        // flag 是 @TableLogic 逻辑删除字段。@TableLogic 只在查询/删除时生效，insert 不会自动填。
        // 这里必须显式填 0，否则新行 flag=NULL，list 自动追加 WHERE flag=0 看不到。
        this.strictInsertFill(metaObject, "flag", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updatedBy", String.class, currentUserIdOrSystem());
    }

    private String currentUserIdOrSystem() {
        try {
            String uid = SecurityUtils.currentUserId();
            return uid != null ? uid : "SYSTEM";
        } catch (Exception e) {
            return "SYSTEM";
        }
    }
}
