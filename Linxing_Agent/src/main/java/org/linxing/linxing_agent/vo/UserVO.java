package org.linxing.linxing_agent.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Integer id;

    private String username;

    private OffsetDateTime createdAt;
}
