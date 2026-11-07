package com.jjy.infrastructure.event.deadletter;

import com.jjy.infrastructure.event.outbox.Outbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 死信服务
 * <p>
 * 5 次重试后入死信。handler=NULL 表示传输层失败。
 *
 * @author jjy-base
 */
@Slf4j
@Service
public class DeadLetterService {

    @Autowired
    private DeadLetterMapper deadLetterMapper;

    public void toDeadLetter(Outbox o, String failureMsg) {
        try {
            deadLetterMapper.insertDeadLetter(
                    o.getEventUid(),
                    "DISPATCHER",
                    o.getPayload(),
                    failureMsg);
            log.error("[DeadLetter] 事件入死信: eventUid={} type={} reason={}",
                    o.getEventUid(), o.getEventType(), failureMsg);
        } catch (Exception e) {
            log.error("[DeadLetter] 写死信失败", e);
        }
    }
}
