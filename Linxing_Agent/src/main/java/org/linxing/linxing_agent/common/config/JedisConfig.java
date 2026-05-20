package org.linxing.linxing_agent.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.JedisPooled;

@Configuration
@Slf4j
public class JedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean
    public JedisPooled jedisPooled() {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);

        JedisPooled jedis;
        if (password != null && !password.isBlank()) {
            jedis = new JedisPooled(poolConfig, host, port, 3000, password, database);
        } else {
            jedis = new JedisPooled(poolConfig, host, port, 3000, null, database);
        }

        log.info("JedisPooled 初始化完成: {}:{}", host, port);
        return jedis;
    }
}
