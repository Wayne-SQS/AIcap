package com.aicap.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 工具:与 FastAPI(python-jose, HS256)签名兼容。
 * 契约:payload {"sub": str(user_id), "exp": epoch_seconds},secret 同 .env JWT_SECRET。
 *
 * <p><b>密钥在这里做启动校验,而不是"没配就用默认值"</b> —— 口径与
 * {@link com.aicap.rag.RagProperties#validateAcl()} 一致:本仓是公开的,一个能用的默认密钥
 * 等于"任何拿到源码的人都能自己签一个 admin token",而那种错法<b>不报错</b>:
 * 服务照常起、接口照常通,只是身份可伪造。所以三种情况直接拒绝启动:
 * 空白、短于 HS256 要求的 32 字节、以及已退役的历史默认值。
 *
 * <p>报错里只打印密钥的 <b>SHA-256 指纹(前 8 位十六进制)</b> —— 它回答"用的是哪把",
 * 而<b>绝不打印密钥本身</b>:异常文本会进日志与错误响应,那等于把密钥换个地方存一遍。
 */
@Slf4j
@Component
public class JwtUtil {

    /** HS256 要求密钥至少 256 bit(见 {@code Keys.hmacShaKeyFor} 的 WeakKeyException)。 */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 密钥长度到 48 字节时 JJWT 会自动改用 HS384、64 字节改 HS512。
     *
     * <p>这不需要拦(HS384 的签名自己仍然安全),但会与"HS256"这条跨语言契约分叉 ——
     * FastAPI 侧按 HS256 校验时会全部 401。属于<b>不报错的静默分叉</b>,所以记一条 WARN,
     * 而不是让排查的人从"登录成功了但另一个服务说我 token 无效"倒推。
     */
    private static final int ALGORITHM_SHIFT_BYTES = 48;

    /**
     * 已退役的开发默认值(曾硬编码在 application.yml)。
     *
     * <p>留着它只为"报错能指名道姓":命中时直接说"这是历史默认值",而不是让人对着一个
     * 长度合法的密钥猜为什么被拒。它<b>不再</b>作为可用密钥。
     */
    private static final String RETIRED_DEV_DEFAULT = "aicap-dev-secret-change-me-please-use-env-0123456789";

    private final SecretKey key;
    private final long expireMinutes;

    public JwtUtil(@Value("${aicap.jwt.secret}") String secret,
                   @Value("${aicap.jwt.expire-minutes}") long expireMinutes) {
        validate(secret);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    public String createToken(Integer userId) {
        Instant exp = Instant.now().plus(expireMinutes, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /** 解析并返回 userId;无效/过期返回 null */
    public Integer parseUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Integer.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 密钥校验:不合格就抛 {@link IllegalStateException},让 Spring 启动直接失败。
     *
     * <p>失败而不是"降级成随机密钥"也是刻意的:随机密钥能让服务起来,但每次重启
     * 所有已发 token 全部失效,表现为"用户莫名其妙被登出",比起不来更难查。
     */
    private static void validate(String secret) {
        String s = secret == null ? "" : secret;
        if (s.isBlank()) {
            throw new IllegalStateException(
                    "未配置 JWT 密钥(aicap.jwt.secret / 环境变量 JWT_SECRET),拒绝以空密钥启动。"
                            + "开发:在 backend\\.env 里设 JWT_SECRET(启动后端.ps1 与 run.ps1 都会注入);"
                            + "生产:在 deploy\\.env 里设(deploy/compose.yaml 已按 ${JWT_SECRET:?...} 失败关闭);"
                            + "测试:由 src/test/resources/application.properties 提供夹具密钥。");
        }
        if (RETIRED_DEV_DEFAULT.equals(s)) {
            throw new IllegalStateException(
                    "JWT 密钥仍是已退役的历史默认值,拒绝启动:那把值随源码公开,任何人都能签出 admin token。"
                            + "请在 backend\\.env / deploy\\.env 里换一把自己的(≥32 字节)。");
        }
        int length = s.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥过短:" + length + " 字节,HS256 至少需要 " + MIN_SECRET_BYTES + " 字节。"
                            + "换一把更长的(密钥指纹 " + fingerprint(s) + ")。");
        }
        log.info("JWT 密钥已加载:{} 字节,指纹 {}", length, fingerprint(s));
        if (length >= ALGORITHM_SHIFT_BYTES) {
            log.warn("JWT 密钥长度 {} 字节 ≥ {}:JJWT 会自动改用更强的 HMAC 算法,"
                            + "与 FastAPI 侧的 HS256 契约分叉(对方会 401)。要么缩短到 32-47 字节,要么两边同步改。",
                    length, ALGORITHM_SHIFT_BYTES);
        }
    }

    /**
     * 密钥指纹:SHA-256 前 4 字节的十六进制。
     *
     * <p>只用于"这是哪一把"的比对(日志/报错里出现),不可逆推密钥 —— 这正是它能进日志的原因。
     */
    private static String fingerprint(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "(指纹不可用)";
        }
    }
}
