package com.jjy.infrastructure.event.idempotent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等占位服务
 * <p>
 * UNIQUE(event_uid, handler) 约束保证同一事件同一订阅者只执行一次。
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class IdempotentService {

    @Autowired
    private EventHandlerLogMapper logMapper;

    /**
     * 占位：第一次返回 true；之后返回 false。
     *
     * @return true=首次进入，false=已处理过
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markIfAbsent(String eventUid, String handler) {
        try {
            logMapper.insertLog(eventUid, handler, 0, null);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("[Idempotent] 已处理: eventUid={} handler={}", eventUid, handler);
            return false;
        }
    }

    /**
     * 标记失败
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String eventUid, String handler, String errorMsg) {
        logMapper.markFailed(eventUid, handler, errorMsg);
    }
}
