package com.jjy.infrastructure.util;

import java.util.UUID;

/**
 * 业务主键生成器（32 位 UUID，去横线）
 *
 * @author jjy-base
 */
public final class IdGen {

    private IdGen() {}

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase();
    }

    /**
     * 业务编号：前缀 + yyyyMMdd + 4 位流水号
     *
     * @param prefix 三位前缀，如 CUS / INB / STM
     * @param seq    流水号（调用方自管，建议从 DB sequence 或 redis 取）
     */
    public static String bizNo(String prefix, long seq) {
        return String.format("%s%s%04d", prefix, DateUtil.todayCompact(), seq);
    }
}
