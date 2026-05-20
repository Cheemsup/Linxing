package org.linxing.linxing_agent.user.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 用户实体类
 * 用于存储系统用户的基本信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    private Integer id;

    private String username;

    private String passwordHash;

    private OffsetDateTime createdAt;
}
