package org.linxing.linxing_agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@MapperScan("org.linxing.linxing_agent.mapper")
public class LinxingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinxingAgentApplication.class, args);
    }
}
