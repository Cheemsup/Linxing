package org.linxing.linxing_agent.user.mapper;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.linxing.linxing_agent.user.entity.User;

@Mapper
public interface UserMapper {

    int insert(User user);

    Optional<User> findById(@Param("id") Integer id);

    Optional<User> findByUsername(@Param("username") String username);

    int update(User user);

    int deleteById(@Param("id") Integer id);
}
