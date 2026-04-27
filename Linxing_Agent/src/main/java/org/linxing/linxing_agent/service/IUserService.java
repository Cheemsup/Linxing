package org.linxing.linxing_agent.service;

import org.linxing.linxing_agent.dto.UserLoginDTO;
import org.linxing.linxing_agent.dto.UserRegisterDTO;
import org.linxing.linxing_agent.vo.UserLoginVO;
import org.linxing.linxing_agent.vo.UserRegisterResult;
import org.linxing.linxing_agent.vo.UserVO;

public interface IUserService {

    UserRegisterResult register(UserRegisterDTO userRegisterDTO);

    UserLoginVO login(UserLoginDTO userLoginDTO);

    void logout();

    UserVO getCurrentUser();
}
