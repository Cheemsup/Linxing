package org.linxing.linxing_agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@MapperScan({"org.linxing.linxing_agent.rag.mapper", "org.linxing.linxing_agent.user.mapper"})
public class LinxingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinxingAgentApplication.class, args);
    }
}
