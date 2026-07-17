package cloud.npcbase.kb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 知识库服务的 Spring Boot 启动入口。
 *
 * @author NPC
 * @date 2026-07-15 18:27:07
 */
@EnableScheduling
@SpringBootApplication
public class KbApplication {

    /**
     * 启动知识库服务。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        // 启动 Spring Boot 知识库应用。
        SpringApplication.run(KbApplication.class, args);
    }
}
