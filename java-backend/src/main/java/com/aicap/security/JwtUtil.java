package com.aicap.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 工具:与 FastAPI(python-jose, HS256)签名兼容。
 * 契约:payload {"sub": str(user_id), "exp": epoch_seconds},secret 同 .env JWT_SECRET。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMinutes;

    public JwtUtil(@Value("${aicap.jwt.secret}") String secret,
                   @Value("${aicap.jwt.expire-minutes}") long expireMinutes) {
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
}
