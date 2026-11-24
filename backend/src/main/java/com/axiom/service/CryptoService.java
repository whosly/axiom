package com.axiom.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感信息加密服务 / Sensitive data encryption service.
 *
 * <p>用 AES-GCM 加密数据源密码。加密密钥从环境变量 DATASOURCE_ENCRYPTION_KEY 读取，
 * 未配置时从机器标识派生，**绝不硬编码在源代码中**。
 * / Encrypts datasource passwords with AES-GCM. The key is read from the environment
 * variable DATASOURCE_ENCRYPTION_KEY, or derived from a machine identifier when absent;
 * it is NEVER hardcoded in source code.
 */
@Slf4j
@Service
public class CryptoService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /** 缓存的密钥 / Cached key. */
    private final byte[] key;

    public CryptoService() {
        this.key = resolveKey();
    }

    /**
     * 解析加密密钥 / Resolve the encryption key.
     *
     * <p>优先环境变量；否则用主机名+用户名派生，保证不同机器密钥不同。
     * / Prefer the env var; otherwise derive from host+user so different machines get different keys.
     */
    private byte[] resolveKey() {
        try {
            String envKey = System.getenv("DATASOURCE_ENCRYPTION_KEY");
            String material = (envKey != null && !envKey.isBlank())
                    ? envKey
                    : System.getenv("HOSTNAME") + "|" + System.getProperty("user.name") + "|axiom";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.Arrays.copyOf(digest.digest(material.getBytes(StandardCharsets.UTF_8)), 32);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve encryption key", e);
        }
    }

    /**
     * 加密 / Encrypt.
     *
     * @param plain 明文 / Plaintext.
     * @return Base64(IV + ciphertext) / Base64-encoded IV and ciphertext.
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * 解密 / Decrypt.
     *
     * <p>解密失败必须显式抛错而不是静默回空串：静默空密码会以"连接失败"的面目出现，
     * 用户排查不到根因（典型场景：换机器/换密钥后所有数据源密码同时失效）。
     * / Decryption failure must surface: silently returning "" turns every stored password
     * into a broken one after a machine or key change, with no root cause visible.
     *
     * @param encrypted Base64(IV + ciphertext) / Encrypted payload.
     * @return 明文 / Plaintext.
     * @throws IllegalStateException 密文损坏或密钥不匹配 / Corrupted ciphertext or key mismatch.
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return "";
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            byte[] iv = java.util.Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherText = java.util.Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 不回显密文与密钥细节，只给出可操作的修复路径 / Do not leak ciphertext or key
            // material; give the user an actionable remedy instead.
            log.warn("[Crypto] decryption failed: {}", e.getMessage());
            throw new IllegalStateException(
                    "凭据解密失败（密文损坏或加密密钥已变更）。请删除并重新录入该数据源，"
                            + "或设置 DATASOURCE_ENCRYPTION_KEY 为原密钥。"
                            + " / Credential decryption failed (corrupted data or key changed). "
                            + "Re-register the datasource or set DATASOURCE_ENCRYPTION_KEY to the original key.");
        }
    }
}
