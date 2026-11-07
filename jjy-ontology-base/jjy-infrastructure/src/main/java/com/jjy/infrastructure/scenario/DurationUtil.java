package com.jjy.infrastructure.scenario;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 M4 step.timeout 字符串（"7d" / "24h" / "5m" / "30s" / "0s"）解析成 Duration。
 *
 * <p>支持单位：d（天） / h（小时） / m（分钟） / s（秒）。不写单位默认秒。</p>
 *
 * @author jjy-base
 */
public final class DurationUtil {

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)([dhmsDHMS])?$");

    private DurationUtil() {}

    /**
     * 解析 timeout 字符串
     *
     * @param spec 例如 "7d" / "24h" / "5m" / "0s" / null
     * @return Duration；null 或 "0s" → Duration.ZERO；解析失败 → Duration.ZERO（超时立即触发）
     */
    public static Duration parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) return Duration.ZERO;
        String s = spec.trim();
        if ("0s".equalsIgnoreCase(s) || "0".equals(s)) return Duration.ZERO;
        Matcher m = PATTERN.matcher(s);
        if (!m.matches()) {
            return Duration.ZERO;
        }
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2).toLowerCase();
        switch (unit) {
            case "d": return Duration.ofDays(n);
            case "h": return Duration.ofHours(n);
            case "m": return Duration.ofMinutes(n);
            case "s": return Duration.ofSeconds(n);
            default:  return Duration.ofSeconds(n);
        }
    }
}
