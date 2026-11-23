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
     * @param encrypted Base64(IV + ciphertext) / Encrypted payload.
     * @return 明文 / Plaintext.
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
            log.warn("[Crypto] decryption failed: {}", e.getMessage());
            return "";
        }
    }
}
