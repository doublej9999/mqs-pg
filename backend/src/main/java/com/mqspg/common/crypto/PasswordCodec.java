package com.mqspg.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据源密码编解码。
 *
 * <p>存储格式：
 * <ul>
 *   <li>{@code {noop}<明文>} —— 仅限 dev / demo，禁止上生产；</li>
 *   <li>{@code {aes}<base64(iv || ciphertext || tag)>} —— 生产使用，AES-256-GCM。</li>
 * </ul>
 *
 * <p>刻意要求**显式前缀**：没有前缀直接抛错，避免「以为加了密其实存了明文」
 * 或反之的静默错误。
 */
@Component
public class PasswordCodec {

    public static final String NOOP = "{noop}";
    public static final String AES = "{aes}";

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey key;

    public PasswordCodec(@Value("${mqs-pg.crypto.key:}") String keyBase64) {
        this.key = (keyBase64 == null || keyBase64.isBlank())
                ? null
                : new SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES");
    }

    /** 解码存储值。 */
    public String decode(String stored) {
        if (stored == null) {
            return null;
        }
        if (stored.startsWith(NOOP)) {
            return stored.substring(NOOP.length());
        }
        if (stored.startsWith(AES)) {
            return decrypt(stored.substring(AES.length()));
        }
        throw new IllegalArgumentException(
                "密码缺少 {noop} 或 {aes} 前缀，拒绝按明文使用");
    }

    /** 编码为 {@code {aes}...}，供运维工具生成。 */
    public String encodeAes(String plain) {
        if (key == null) {
            throw new IllegalStateException("未配置 mqs-pg.crypto.key，无法加密");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return AES + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    private String decrypt(String payload) {
        if (key == null) {
            throw new IllegalStateException("数据源使用了 {aes} 密码，但未配置 mqs-pg.crypto.key");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("密文长度非法");
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_LENGTH));
            return new String(c.doFinal(raw, GCM_IV_LENGTH, raw.length - GCM_IV_LENGTH),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("密码解密失败（密钥不匹配或密文损坏）", e);
        }
    }
}
