package com.aicap.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码校验:兼容现有库中 passlib 生成的 $2b$ bcrypt 哈希。
 * spring-security-crypto 的 BCryptPasswordEncoder 使用 $2a$ 版本号生成,
 * 但 matches() 底层 BCrypt.checkpw 同时识别 $2a/$2b/$2y 前缀,可直接校验存量数据。
 */
@Component
public class PasswordUtil {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public boolean verify(String plain, String hashed) {
        if (plain == null || hashed == null) return false;
        try {
            return encoder.matches(plain, hashed);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 新用户/重置密码用(生成 $2a$;未来新增用户统一走此入口) */
    public String hash(String plain) {
        return encoder.encode(plain);
    }
}
