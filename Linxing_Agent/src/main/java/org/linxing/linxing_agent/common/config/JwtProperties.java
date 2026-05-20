package org.linxing.linxing_agent.common.config;

import lombok.Data;
import org.linxing.linxing_agent.constant.JwtClaims;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secretKey;

    private Long ttl = JwtClaims.DEFAULT_TTL;

    private String tokenName = JwtClaims.DEFAULT_TOKEN_NAME;

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
