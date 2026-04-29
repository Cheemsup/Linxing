package org.linxing.linxing_agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.dto.UserLoginDTO;
import org.linxing.linxing_agent.dto.UserRegisterDTO;
import org.linxing.linxing_agent.vo.UserLoginVO;
import org.linxing.linxing_agent.vo.UserRegisterResult;
import org.linxing.linxing_agent.result.Result;
import org.linxing.linxing_agent.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
@Slf4j
@Validated
public class UserController {

    @Autowired
    private IUserService userService;

    @PostMapping("/register")
    public Result<UserRegisterResult> register(@RequestBody @Validated UserRegisterDTO userRegisterDTO) {
        UserRegisterResult result = userService.register(userRegisterDTO);
        return Result.success(result);
    }

    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) {

        UserLoginVO userLoginVO = userService.login(userLoginDTO);

        log.info("用户登录成功: userId={}, username={}",
                userLoginVO.getId(), userLoginVO.getUsername());

        return Result.success(userLoginVO);
    }

    @PostMapping("/logout")
    public Result<String> logout() {
        userService.logout();
        return Result.success("登出成功");
    }
}
