package cn.bugstack.types.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description Token加密解密工具类
 * @create 2025-11-01
 */
public class TokenUtil {

    // AES加密密钥（16字节）
    private static final String AES_KEY = "GroupBuyMarket16";

    /**
     * 根据 tokenKey 生成加密后的 tokenValue
     * 
     * @param tokenKey 原始key，格式：token:lock_order:activityId:userId
     * @return 加密后的tokenValue
     */
    public static String getTokenValueByKey(String tokenKey) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(tokenKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Token加密失败", e);
        }
    }

    /**
     * 根据加密的 tokenValue 解密得到原始的 tokenKey
     * 
     * @param tokenValue 加密后的token
     * @return 解密后的tokenKey
     */
    public static String getTokenKeyByValue(String tokenValue) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decoded = Base64.getDecoder().decode(tokenValue);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Token解密失败", e);
        }
    }
}

