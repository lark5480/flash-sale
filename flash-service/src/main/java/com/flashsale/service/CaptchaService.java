package com.flashsale.service;

import com.flashsale.common.constant.RedisConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 图形验证码服务 —— 基于数学运算 + SVG 生成
 * <p>
 * 生成数学表达式（加减法），以 SVG 形式返回，
 * 答案存入 Redis（TTL 5 分钟），支持一次验证后立即失效。
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);
    private final StringRedisTemplate stringRedisTemplate;
    private final Random random = new Random();

    public CaptchaService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成验证码
     *
     * @return Map 包含 captchaId 和 svg
     */
    public Map<String, String> generate() {
        String captchaId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 生成数学表达式
        int a = random.nextInt(10) + 1;
        int b = random.nextInt(10) + 1;
        int op = random.nextInt(3); // 0=add, 1=sub, 2=mul
        String expression;
        int answer;
        switch (op) {
            case 0:
                expression = a + " + " + b;
                answer = a + b;
                break;
            case 1:
                if (a < b) { int tmp = a; a = b; b = tmp; }
                expression = a + " - " + b;
                answer = a - b;
                break;
            default:
                a = random.nextInt(9) + 1;
                b = random.nextInt(9) + 1;
                expression = a + " x " + b;
                answer = a * b;
                break;
        }

        // 存储答案到 Redis
        String redisKey = RedisConstants.CAPTCHA_KEY + captchaId;
        stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(answer),
                RedisConstants.CAPTCHA_TTL, TimeUnit.SECONDS);

        // 生成 SVG
        String svg = generateSvg(expression);
        log.debug("[验证码] 生成成功, captchaId={}, expression={}", captchaId, expression);

        return Map.of("captchaId", captchaId, "svg", svg);
    }

    /**
     * 校验验证码，校验后立即失效（一次性）
     *
     * @return true = 验证通过
     */
    public boolean validate(String captchaId, String answer) {
        if (captchaId == null || answer == null || captchaId.isBlank() || answer.isBlank()) {
            return false;
        }
        String redisKey = RedisConstants.CAPTCHA_KEY + captchaId;
        String stored = stringRedisTemplate.opsForValue().get(redisKey);
        // 一次性：无论对错都删除
        stringRedisTemplate.delete(redisKey);
        if (stored == null) {
            return false;
        }
        return stored.equals(answer.trim());
    }

    private String generateSvg(String expression) {
        String text = expression + " = ?";
        // 噪声线
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            int x1 = random.nextInt(160);
            int y1 = random.nextInt(50);
            int x2 = random.nextInt(160);
            int y2 = random.nextInt(50);
            String color = String.format("rgb(%d,%d,%d)",
                    random.nextInt(100) + 80, random.nextInt(100) + 80, random.nextInt(100) + 80);
            noise.append(String.format(
                    "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"0.8\" opacity=\"0.4\"/>",
                    x1, y1, x2, y2, color));
        }
        // 噪声点
        for (int i = 0; i < 20; i++) {
            int cx = random.nextInt(160);
            int cy = random.nextInt(50);
            String color = String.format("rgb(%d,%d,%d)",
                    random.nextInt(150) + 50, random.nextInt(150) + 50, random.nextInt(150) + 50);
            noise.append(String.format(
                    "<circle cx=\"%d\" cy=\"%d\" r=\"1.2\" fill=\"%s\" opacity=\"0.5\"/>",
                    cx, cy, color));
        }

        // 每个字符随机偏移
        StringBuilder chars = new StringBuilder();
        int startX = 14;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int x = startX + i * 20 + random.nextInt(6) - 3;
            int y = 32 + random.nextInt(10) - 5;
            double rotate = random.nextInt(30) - 15;
            String color = String.format("rgb(%d,%d,%d)",
                    random.nextInt(100) + 100, random.nextInt(100) + 100, random.nextInt(100) + 155);
            String escaped = c == '<' ? "&lt;" : c == '>' ? "&gt;" : c == '&' ? "&amp;" : String.valueOf(c);
            chars.append(String.format(
                    "<text x=\"%d\" y=\"%d\" fill=\"%s\" font-size=\"%d\" font-family=\"Arial,sans-serif\" " +
                    "font-weight=\"bold\" transform=\"rotate(%.0f %d %d)\">%s</text>",
                    x, y, color, random.nextInt(6) + 20, rotate, x, y, escaped));
        }

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"160\" height=\"50\" viewBox=\"0 0 160 50\">"
                + "<rect width=\"160\" height=\"50\" fill=\"#1a1a2e\" rx=\"6\"/>"
                + noise
                + chars
                + "</svg>";
    }
}
