package cloud.npcbase.kb.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册知识库配置属性对象。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@Configuration
@EnableConfigurationProperties(KbProperties.class)
public class PropertiesConfig {
}
