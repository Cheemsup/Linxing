package org.linxing.linxing_agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secretKey;

    private Long ttl = 1800000L;

    private String tokenName = "Authorization";

    public String getUserSecretKey() {
        return secretKey;
    }

    public Long getUserTtl() {
        return ttl;
    }

    public String getUserTokenName() {
        return tokenName;
    }
}
