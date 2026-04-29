package org.linxing.linxing_agent.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.JwtClaimsConstant;
import org.linxing.linxing_agent.context.BaseContext;
import org.linxing.linxing_agent.dto.UserLoginDTO;
import org.linxing.linxing_agent.dto.UserRegisterDTO;
import org.linxing.linxing_agent.vo.UserLoginVO;
import org.linxing.linxing_agent.vo.UserRegisterResult;
import org.linxing.linxing_agent.entity.User;
import org.linxing.linxing_agent.exception.AccountNotFoundException;
import org.linxing.linxing_agent.exception.PasswordIncorrectException;
import org.linxing.linxing_agent.exception.UsernameDuplicateException;
import org.linxing.linxing_agent.mapper.UserMapper;
import org.linxing.linxing_agent.config.JwtProperties;
import org.linxing.linxing_agent.service.IUserService;
import org.linxing.linxing_agent.utils.JwtUtil;
import org.linxing.linxing_agent.utils.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class UserServiceImpl implements IUserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public UserRegisterResult register(UserRegisterDTO userRegisterDTO) {
        String username = userRegisterDTO.getUsername();
        String password = userRegisterDTO.getPassword();
        String confirmPassword = userRegisterDTO.getConfirmPassword();

        log.info("用户注册请求: username={}", username);

        if (!password.equals(confirmPassword)) {
            throw new IllegalArgumentException("两次输入的密码不一致");
        }

        Optional<User> existingUser = userMapper.findByUsername(username);
        if (existingUser.isPresent()) {
            log.warn("用户名已存在: {}", username);
            throw new UsernameDuplicateException("用户名已被占用");
        }

        String encodedPassword = PasswordEncoder.encode(password);

        User newUser = User.builder()
                .username(username)
                .passwordHash(encodedPassword)
                .createdAt(OffsetDateTime.now())
                .build();

        userMapper.insert(newUser);

        log.info("用户注册成功: userId={}, username={}", newUser.getId(), username);

        return UserRegisterResult.builder()
                .id(newUser.getId())
                .username(username)
                .build();
    }

    @Override
    public UserLoginVO login(UserLoginDTO userLoginDTO) {
        String username = userLoginDTO.getUsername();
        String password = userLoginDTO.getPassword();

        log.info("用户登录请求: username={}", username);

        Optional<User> userOptional = userMapper.findByUsername(username);
        if (userOptional.isEmpty()) {
            log.warn("用户不存在: {}", username);
            throw new AccountNotFoundException("用户名或密码错误");
        }

        User user = userOptional.get();

        if (!PasswordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("密码错误: username={}", username);
            throw new PasswordIncorrectException("用户名或密码错误");
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        claims.put(JwtClaimsConstant.USERNAME, user.getUsername());

        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims
        );

        log.info("用户登录成功: userId={}, username={}", user.getId(), username);

        return UserLoginVO.builder()
                .token(token)
                .id(user.getId())
                .username(user.getUsername())
                .build();
    }

    @Override
    public void logout() {
        Long currentUserId = BaseContext.getCurrentId();
        log.info("用户登出: userId={}", currentUserId);
    }
}
