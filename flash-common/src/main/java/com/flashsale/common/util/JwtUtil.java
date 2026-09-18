package com.flashsale.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
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

    /** HS256 要求密钥不短于 256 bit（32 字节），由 RFC 7518 规定 */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret}")
    private String secret;

    /** Access Token 有效期（毫秒），默认 30 分钟 */
    @Value("${jwt.expiration:1800000}")
    private long expiration;

    /** Refresh Token 有效期（毫秒），默认 7 天 */
    @Value("${jwt.refresh-expiration:604800000}")
    private long refreshExpiration;

    /**
     * 启动即校验签名密钥。
     * <p>
     * 密钥只在签发/验签时才被读取，若不在此处拦截，配置为空的应用也能正常启动，
     * 直到第一个登录请求才炸——而 {@code hmacShaKeyFor} 对短密钥的行为又不一致，
     * 等于把「配置漏了」变成线上事故。缺失或不足 32 字节一律拒绝启动。
     */
    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("jwt.secret 未配置：请通过环境变量 JWT_SECRET 注入一把随机密钥"
                    + "（本地跑法见 .env.example，容器编排见 docker-compose.yml）");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret 长度不足：HS256 至少需要 "
                    + MIN_SECRET_BYTES + " 字节随机密钥，当前为 "
                    + secret.getBytes(StandardCharsets.UTF_8).length + " 字节");
        }
    }

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
