package com.flashsale.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类 —— Token 签发、验签、解析.
 * <p>
 * 签名算法 HMAC-SHA256，secret 通过 {@code ${jwt.secret}} 注入.
 * Payload 结构: {@code { sub: userId, role: "USER"|"ADMIN", iat, exp }}.
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret:flash-sale-secret-key-min-256-bits-long-for-hs256}")
    private String secret;

    /** Access Token 有效期（毫秒），默认 30 分钟 */
    @Value("${jwt.expiration:1800000}")
    private long expiration;

    /** Refresh Token 有效期（毫秒），默认 7 天 */
    @Value("${jwt.refresh-expiration:604800000}")
    private long refreshExpiration;

    private SecretKey getSecretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 Access Token.
     *
     * @param userId 用户 ID
     * @param role   角色 {@code "USER"} 或 {@code "ADMIN"}
     * @return JWT 字符串
     */
    public String generateToken(Long userId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(getSecretKey())
                .compact();
    }

    /**
     * 签发 Refresh Token（不含 role，仅用于换发新的 Access Token）.
     *
     * @param userId 用户 ID
     * @return JWT 字符串
     */
    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpiration))
                .signWith(getSecretKey())
                .compact();
    }

    /**
     * 验签并解析 Token.
     *
     * @param token JWT 字符串
     * @return Claims 对象
     * @throws io.jsonwebtoken.JwtException 签名无效/格式错误/已过期
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSecretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 提取用户 ID.
     *
     * @param token JWT 字符串
     * @return 用户 ID
     */
    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    /**
     * 从 Token 提取角色.
     *
     * @param token JWT 字符串
     * @return 角色字符串 {@code "USER"} 或 {@code "ADMIN"}
     */
    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    /**
     * 判断 Token 是否已过期.
     *
     * @param token JWT 字符串
     * @return true 表示已过期
     */
    public boolean isTokenExpired(String token) {
        return parseToken(token).getExpiration().before(new Date());
    }
}
