package com.jjy.infrastructure.yaml;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 工具。
 *
 * 用途：manifest.json 中登记的每个模型文件 SHA-256，启动期与 classpath:models/ 实际内容比对，
 * 不一致仅 WARN 不阻断启动（按方法论 §14.4 约定）。
 *
 * @author jjy-base
 */
@Slf4j
public final class Sha256Util {

    private Sha256Util() {}

    /**
     * 计算输入流的 SHA-256（十六进制小写）。流不关闭。
     */
    public static String digest(InputStream in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (IOException e) {
            throw new RuntimeException("读取输入流失败", e);
        }
    }

    /**
     * 比对两个 hex（小写），null/空视为"未登记"，返回 true（不阻断）。
     */
    public static boolean verify(String expected, String actual) {
        if (expected == null || expected.isEmpty()) {
            log.debug("SHA-256 未登记，跳过校验（actual={}）", actual);
            return true;
        }
        boolean ok = expected.equalsIgnoreCase(actual);
        if (!ok) {
            log.warn("SHA-256 不一致：expected={}, actual={}", expected, actual);
        }
        return ok;
    }
}
