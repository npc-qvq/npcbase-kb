package cloud.npcbase.kb.access;

import cloud.npcbase.kb.config.AccessProperties;
import cloud.npcbase.kb.config.KbProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 校验唯一访问密钥、签发访问凭证并管理匿名访客公开体验额度。
 *
 * @author NPC
 * @date 2026-08-06 16:01:37
 */
@Service
public class AccessService {

    /**
     * 保存解锁凭证的 HttpOnly Cookie 名称。
     */
    public static final String ACCESS_COOKIE_NAME = "kb_access";

    /**
     * 保存匿名访客标识的 HttpOnly Cookie 名称。
     */
    public static final String VISITOR_COOKIE_NAME = "kb_visitor";

    /**
     * PBKDF2 密钥派生算法名称。
     */
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    /**
     * HMAC 访问凭证签名算法名称。
     */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 匿名访客标识和 Redis 计数的最长保留时间。
     */
    private static final Duration VISITOR_TTL = Duration.ofDays(366);

    /**
     * 每日额度键保留时间，覆盖跨午夜的部署时钟差异。
     */
    private static final Duration DAILY_TTL = Duration.ofDays(2);

    /**
     * 知识库访问控制配置。
     */
    private final AccessProperties properties;

    /**
     * 保存访客额度和密钥尝试次数的 Redis 客户端。
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 生成不可预测会话标识和访客标识的安全随机数生成器。
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建知识库访问控制服务。
     *
     * @param kbProperties 知识库根配置
     * @param redisTemplate Redis 字符串访问客户端
     */
    public AccessService(KbProperties kbProperties, StringRedisTemplate redisTemplate) {
        this.properties = kbProperties.getAccess();
        this.redisTemplate = redisTemplate;
    }

    /**
     * 返回当前请求是否持有有效且未过期的解锁凭证。
     *
     * @param request 当前 HTTP 请求
     * @return 解锁凭证合法时返回 true
     */
    public boolean isUnlocked(HttpServletRequest request) {
        String token = findCookie(request, ACCESS_COOKIE_NAME);
        return verifyAccessToken(token);
    }

    /**
     * 查询当前浏览器的访问权限和剩余公开体验次数。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @return 当前访问状态
     */
    public AccessStatusResponse status(HttpServletRequest request, HttpServletResponse response) {
        boolean unlocked = isUnlocked(request);
        int messageLimit = Math.max(0, properties.getPublicMessageLimit());
        int remainingMessages = messageLimit;
        if (!unlocked) {
            String visitorId = ensureVisitorId(request, response);
            long usedMessages = readCounter(visitorQuotaKey(visitorId));
            remainingMessages = Math.max(0, messageLimit - Math.toIntExact(Math.min(usedMessages, messageLimit)));
        }
        return new AccessStatusResponse(unlocked, normalize(properties.getDemoConversationId()), remainingMessages,
                messageLimit, publicProvider());
    }

    /**
     * 校验用户输入的唯一密钥并签发限时解锁凭证。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param unlockRequest 用户提交的唯一密钥
     * @return 解锁后的访问状态
     */
    public AccessStatusResponse unlock(HttpServletRequest request,
                                       HttpServletResponse response,
                                       AccessUnlockRequest unlockRequest) {
        validateUnlockConfiguration();
        String attemptKey = unlockAttemptKey(clientIp(request));
        long attempts = incrementCounter(attemptKey, Duration.ofMinutes(Math.max(1, properties.getUnlockWindowMinutes())));
        if (attempts > Math.max(1, properties.getUnlockMaxAttempts())) {
            throw new AccessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "密钥尝试次数过多，请稍后再试");
        }
        if (!verifyAccessKey(unlockRequest == null ? null : unlockRequest.key())) {
            throw new AccessException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_KEY", "访问密钥不正确");
        }
        // 密钥验证成功后清除当前网络地址的失败次数。
        redisDelete(attemptKey);
        addCookie(response, ACCESS_COOKIE_NAME, createAccessToken(),
                Duration.ofHours(Math.max(1, properties.getTokenTtlHours())), true);
        ensureVisitorId(request, response);
        int limit = Math.max(0, properties.getPublicMessageLimit());
        return new AccessStatusResponse(true, normalize(properties.getDemoConversationId()), limit, limit, publicProvider());
    }

    /**
     * 删除当前浏览器的解锁凭证，使页面恢复公开只读模式。
     *
     * @param response 当前 HTTP 响应
     */
    public void lock(HttpServletResponse response) {
        addCookie(response, ACCESS_COOKIE_NAME, "", Duration.ZERO, true);
    }

    /**
     * 校验当前请求已经通过唯一密钥解锁。
     *
     * @param request 当前 HTTP 请求
     * @throws AccessException 当请求未解锁时抛出
     */
    public void requireUnlocked(HttpServletRequest request) {
        if (!isUnlocked(request)) {
            throw new AccessException(HttpStatus.FORBIDDEN, "KEY_REQUIRED", "请输入访问密钥后再执行此操作");
        }
    }

    /**
     * 为匿名访客的一次测试会话发送操作预占访客、网络和全站额度。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param conversationId 目标会话主键
     * @return 本次已预占的 Redis 计数键
     */
    public PublicQuotaReservation reservePublicMessage(HttpServletRequest request,
                                                       HttpServletResponse response,
                                                       String conversationId) {
        validateDemoConversation(conversationId);
        String visitorId = ensureVisitorId(request, response);
        List<String> reservedKeys = new ArrayList<>();
        reserveCounter(visitorQuotaKey(visitorId), Math.max(0, properties.getPublicMessageLimit()),
                VISITOR_TTL, "PUBLIC_QUOTA_EXHAUSTED", "公开体验次数已用完，请输入访问密钥继续", reservedKeys);
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        reserveCounter(ipDailyQuotaKey(clientIp(request), day), Math.max(1, properties.getPublicIpDailyLimit()),
                DAILY_TTL, "PUBLIC_RATE_LIMITED", "当前网络今天的公开体验次数已用完", reservedKeys);
        reserveCounter(globalDailyQuotaKey(day), Math.max(1, properties.getPublicGlobalDailyLimit()),
                DAILY_TTL, "PUBLIC_RATE_LIMITED", "今天的公开体验额度已用完，请稍后再试", reservedKeys);
        return new PublicQuotaReservation(List.copyOf(reservedKeys));
    }

    /**
     * 释放一次失败公开消息请求预占的全部额度。
     *
     * @param reservation 待补偿的额度预占信息
     */
    public void releasePublicMessage(PublicQuotaReservation reservation) {
        if (reservation == null || reservation.redisKeys() == null) {
            return;
        }
        for (String redisKey : reservation.redisKeys()) {
            decrementCounter(redisKey);
        }
    }

    /**
     * 获取公开测试会话固定使用的提供商名称。
     *
     * @return 提供商标准名称
     */
    public String publicProvider() {
        String provider = normalize(properties.getPublicProvider());
        return provider == null ? "zhipu" : provider;
    }

    /**
     * 校验目标会话是服务器配置的唯一公开测试会话。
     *
     * @param conversationId 目标会话主键
     */
    private void validateDemoConversation(String conversationId) {
        String demoConversationId = normalize(properties.getDemoConversationId());
        if (demoConversationId == null) {
            throw new AccessException(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_NOT_CONFIGURED",
                    "公开测试会话尚未配置");
        }
        if (!demoConversationId.equals(conversationId)) {
            throw new AccessException(HttpStatus.FORBIDDEN, "KEY_REQUIRED",
                    "该会话为只读内容，输入访问密钥后才能提问");
        }
    }

    /**
     * 预占单个 Redis 额度，并在超限时回滚本次已经预占的其他额度。
     *
     * @param redisKey Redis 计数键
     * @param limit 最大允许次数
     * @param ttl 计数键有效期
     * @param code 超限错误码
     * @param message 超限提示
     * @param reservedKeys 本次已预占的键
     */
    private void reserveCounter(String redisKey,
                                int limit,
                                Duration ttl,
                                String code,
                                String message,
                                List<String> reservedKeys) {
        long count = incrementCounter(redisKey, ttl);
        if (count > limit) {
            decrementCounter(redisKey);
            rollbackCounters(reservedKeys);
            throw new AccessException(HttpStatus.FORBIDDEN, code, message);
        }
        reservedKeys.add(redisKey);
    }

    /**
     * 回滚本次请求已经递增的 Redis 额度键。
     *
     * @param redisKeys 待回滚的 Redis 键
     */
    private void rollbackCounters(List<String> redisKeys) {
        for (String redisKey : redisKeys) {
            decrementCounter(redisKey);
        }
        redisKeys.clear();
    }

    /**
     * 验证 PBKDF2 格式的密钥哈希。
     *
     * @param key 用户输入的明文密钥
     * @return 密钥匹配时返回 true
     */
    private boolean verifyAccessKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            String[] parts = properties.getKeyHash().split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expectedHash = Base64.getUrlDecoder().decode(parts[3]);
            PBEKeySpec keySpec = new PBEKeySpec(key.toCharArray(), salt, iterations, expectedHash.length * Byte.SIZE);
            byte[] actualHash;
            try {
                actualHash = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(keySpec).getEncoded();
            } finally {
                keySpec.clearPassword();
            }
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 创建带过期时间和 HMAC 签名的访问凭证。
     *
     * @return 可写入 HttpOnly Cookie 的访问凭证
     */
    private String createAccessToken() {
        long expiresAt = System.currentTimeMillis() + Duration.ofHours(Math.max(1, properties.getTokenTtlHours())).toMillis();
        String payload = UUID.randomUUID() + "." + expiresAt;
        return payload + "." + sign(payload);
    }

    /**
     * 校验访问凭证签名和有效期。
     *
     * @param token Cookie 中的访问凭证
     * @return 凭证合法且未过期时返回 true
     */
    private boolean verifyAccessToken(String token) {
        if (token == null || token.isBlank() || normalize(properties.getTokenSecret()) == null) {
            return false;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            String payload = parts[0] + "." + parts[1];
            byte[] expectedSignature = Base64.getUrlDecoder().decode(parts[2]);
            byte[] actualSignature = Base64.getUrlDecoder().decode(sign(payload));
            long expiresAt = Long.parseLong(parts[1]);
            return expiresAt > System.currentTimeMillis()
                    && MessageDigest.isEqual(expectedSignature, actualSignature);
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 使用服务器私钥计算指定文本的 HMAC 签名。
     *
     * @param value 待签名文本
     * @return URL 安全的 Base64 签名
     */
    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getTokenSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AccessException(HttpStatus.SERVICE_UNAVAILABLE, "ACCESS_NOT_CONFIGURED",
                    "访问凭证配置无效");
        }
    }

    /**
     * 创建或复用匿名访客标识，并确保新标识通过 HttpOnly Cookie 保存。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @return 匿名访客标识
     */
    private String ensureVisitorId(HttpServletRequest request, HttpServletResponse response) {
        String visitorId = findCookie(request, VISITOR_COOKIE_NAME);
        if (visitorId != null && visitorId.matches("[A-Za-z0-9_-]{20,100}")) {
            return visitorId;
        }
        byte[] randomBytes = new byte[24];
        secureRandom.nextBytes(randomBytes);
        visitorId = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        addCookie(response, VISITOR_COOKIE_NAME, visitorId, VISITOR_TTL, true);
        return visitorId;
    }

    /**
     * 从请求 Cookie 中查询指定名称的值。
     *
     * @param request 当前 HTTP 请求
     * @param name Cookie 名称
     * @return Cookie 值；不存在时返回 null
     */
    private String findCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 写入安全 Cookie。
     *
     * @param response 当前 HTTP 响应
     * @param name Cookie 名称
     * @param value Cookie 值
     * @param maxAge Cookie 有效期
     * @param httpOnly 是否禁止前端脚本读取
     */
    private void addCookie(HttpServletResponse response,
                           String name,
                           String value,
                           Duration maxAge,
                           boolean httpOnly) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(properties.isCookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 校验解锁所需的密钥哈希和凭证签名私钥已经配置。
     */
    private void validateUnlockConfiguration() {
        if (normalize(properties.getKeyHash()) == null || normalize(properties.getTokenSecret()) == null) {
            throw new AccessException(HttpStatus.SERVICE_UNAVAILABLE, "ACCESS_NOT_CONFIGURED",
                    "服务器尚未配置访问密钥");
        }
    }

    /**
     * 读取 Redis 计数值。
     *
     * @param redisKey Redis 计数键
     * @return 当前计数；不存在时返回零
     */
    private long readCounter(String redisKey) {
        try {
            String value = redisTemplate.opsForValue().get(redisKey);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception exception) {
            throw redisUnavailable();
        }
    }

    /**
     * 原子递增 Redis 计数并为新键设置有效期。
     *
     * @param redisKey Redis 计数键
     * @param ttl 键有效期
     * @return 递增后的计数
     */
    private long incrementCounter(String redisKey, Duration ttl) {
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, Math.max(1, ttl.toSeconds()), TimeUnit.SECONDS);
            }
            return count == null ? 0L : count;
        } catch (Exception exception) {
            throw redisUnavailable();
        }
    }

    /**
     * 原子递减 Redis 计数，避免请求失败仍消耗公开额度。
     *
     * @param redisKey Redis 计数键
     */
    private void decrementCounter(String redisKey) {
        try {
            Long count = redisTemplate.opsForValue().decrement(redisKey);
            if (count != null && count < 0L) {
                redisTemplate.opsForValue().set(redisKey, "0");
            }
        } catch (Exception exception) {
            throw redisUnavailable();
        }
    }

    /**
     * 删除指定 Redis 键。
     *
     * @param redisKey 待删除的 Redis 键
     */
    private void redisDelete(String redisKey) {
        try {
            redisTemplate.delete(redisKey);
        } catch (Exception exception) {
            throw redisUnavailable();
        }
    }

    /**
     * 构建匿名访客累计消息次数 Redis 键。
     *
     * @param visitorId 匿名访客标识
     * @return Redis 键
     */
    private String visitorQuotaKey(String visitorId) {
        return "kb:access:visitor:" + fingerprint(visitorId);
    }

    /**
     * 构建网络地址每日消息次数 Redis 键。
     *
     * @param ip 客户端网络地址
     * @param day UTC 日期
     * @return Redis 键
     */
    private String ipDailyQuotaKey(String ip, String day) {
        return "kb:access:ip:" + day + ":" + fingerprint(ip);
    }

    /**
     * 构建全站每日消息次数 Redis 键。
     *
     * @param day UTC 日期
     * @return Redis 键
     */
    private String globalDailyQuotaKey(String day) {
        return "kb:access:global:" + day;
    }

    /**
     * 构建密钥尝试次数 Redis 键。
     *
     * @param ip 客户端网络地址
     * @return Redis 键
     */
    private String unlockAttemptKey(String ip) {
        return "kb:access:unlock:" + fingerprint(ip);
    }

    /**
     * 使用服务器私钥对敏感标识生成不可逆指纹。
     *
     * @param value 待隐藏的客户端标识
     * @return URL 安全指纹
     */
    private String fingerprint(String value) {
        validateUnlockConfiguration();
        return sign(value == null ? "" : value);
    }

    /**
     * 获取客户端网络地址，仅在明确启用可信代理时读取转发请求头。
     *
     * @param request 当前 HTTP 请求
     * @return 客户端网络地址
     */
    private String clientIp(HttpServletRequest request) {
        if (properties.isTrustForwardedFor()) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 去除配置值首尾空格，并将空值转换为 null。
     *
     * @param value 原始配置值
     * @return 规范化后的配置值
     */
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 创建 Redis 不可用异常，公开写操作在额度无法确认时按安全原则拒绝。
     *
     * @return Redis 服务不可用异常
     */
    private AccessException redisUnavailable() {
        return new AccessException(HttpStatus.SERVICE_UNAVAILABLE, "ACCESS_STORE_UNAVAILABLE",
                "访问控制服务暂时不可用，请稍后再试");
    }
}
