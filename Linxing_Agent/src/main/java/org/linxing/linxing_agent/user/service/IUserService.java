package org.linxing.linxing_agent.user.service;

import org.linxing.linxing_agent.user.dto.UserLoginDTO;
import org.linxing.linxing_agent.user.dto.UserRegisterDTO;
import org.linxing.linxing_agent.user.vo.UserLoginVO;
import org.linxing.linxing_agent.user.vo.UserRegisterResult;

public interface IUserService {

    UserRegisterResult register(UserRegisterDTO userRegisterDTO);

    UserLoginVO login(UserLoginDTO userLoginDTO);

    void logout();
}
