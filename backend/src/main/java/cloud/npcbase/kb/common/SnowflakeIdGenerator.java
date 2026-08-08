package cloud.npcbase.kb.common;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

/**
 * 统一生成和识别数据库业务实体使用的 Snowflake 主键。
 *
 * @author NPC
 * @date 2026-08-08 23:45:00
 */
public final class SnowflakeIdGenerator {

    /**
     * 禁止实例化无状态主键工具类。
     */
    private SnowflakeIdGenerator() {
    }

    /**
     * 生成一个十进制字符串形式的 Snowflake 主键。
     *
     * @return Snowflake 主键字符串
     */
    public static String nextId() {
        return IdWorker.getIdStr();
    }

    /**
     * 判断给定主键是否为可转换成正 long 的 Snowflake 数字字符串。
     *
     * @param id 待判断的主键
     * @return 主键符合 Snowflake 数字格式时返回 true
     */
    public static boolean isSnowflakeId(String id) {
        if (id == null || id.isBlank() || id.length() > 19) {
            return false;
        }
        for (int index = 0; index < id.length(); index++) {
            if (!Character.isDigit(id.charAt(index))) {
                return false;
            }
        }
        try {
            return Long.parseLong(id) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /**
     * 将已经校验过的 Snowflake 字符串转换为长整型。
     *
     * @param id Snowflake 主键字符串
     * @return Snowflake 长整型数值
     * @throws IllegalArgumentException 当主键不是 Snowflake 数字格式时抛出
     */
    public static long toLong(String id) {
        if (!isSnowflakeId(id)) {
            throw new IllegalArgumentException("主键不是有效的 Snowflake ID: " + id);
        }
        return Long.parseLong(id);
    }
}
