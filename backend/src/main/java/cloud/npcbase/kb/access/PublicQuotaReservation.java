package cloud.npcbase.kb.access;

import java.util.List;

/**
 * 保存一次公开消息请求已经预占的 Redis 计数键，失败时用于原子补偿。
 *
 * @param redisKeys 本次请求已经递增的全部 Redis 计数键
 * @author NPC
 * @date 2026-08-06 16:01:37
 */
public record PublicQuotaReservation(List<String> redisKeys) {
}
