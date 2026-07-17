package cloud.npcbase.kb.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 MyBatis-Plus 的 MySQL 分页拦截器。
 *
 * @author NPC
 * @date 2026-07-16 10:09:20
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 创建 MyBatis-Plus 分页拦截器。
     *
     * @return 已注册 MySQL 分页处理器的 MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
