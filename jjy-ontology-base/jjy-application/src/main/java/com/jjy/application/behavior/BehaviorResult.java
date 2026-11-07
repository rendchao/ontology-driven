package com.jjy.application.behavior;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为执行结果
 *
 * <p>包含：成功/失败标识、业务主键、状态变更、产生的事件（用于同步响应）</p>
 *
 * @author jjy-base
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean success;
    private String aggregateId;
    private String status;
    private Map<String, Object> data;
    private List<String> producedEventIds;
    private String message;

    public static BehaviorResult ok() {
        return new BehaviorResult(true, null, null, new HashMap<>(), null, "OK");
    }

    public static BehaviorResult ok(String aggregateId) {
        return new BehaviorResult(true, aggregateId, null, new HashMap<>(), null, "OK");
    }

    public static BehaviorResult ok(String aggregateId, String status) {
        return new BehaviorResult(true, aggregateId, status, new HashMap<>(), null, "OK");
    }

    public static BehaviorResult ok(String aggregateId, String status, Map<String, Object> data) {
        return new BehaviorResult(true, aggregateId, status, data, null, "OK");
    }

    public BehaviorResult data(String key, Object value) {
        if (this.data == null) this.data = new HashMap<>();
        this.data.put(key, value);
        return this;
    }

    public BehaviorResult data(Map<String, Object> map) {
        if (this.data == null) this.data = new HashMap<>();
        if (map != null) this.data.putAll(map);
        return this;
    }

    public BehaviorResult events(List<String> eventIds) {
        this.producedEventIds = eventIds;
        return this;
    }
}
