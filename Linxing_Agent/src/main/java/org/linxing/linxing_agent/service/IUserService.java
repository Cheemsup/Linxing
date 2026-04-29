package org.linxing.linxing_agent.service;

import org.linxing.linxing_agent.dto.UserLoginDTO;
import org.linxing.linxing_agent.dto.UserRegisterDTO;
import org.linxing.linxing_agent.vo.UserLoginVO;
import org.linxing.linxing_agent.vo.UserRegisterResult;

public interface IUserService {

    UserRegisterResult register(UserRegisterDTO userRegisterDTO);

    UserLoginVO login(UserLoginDTO userLoginDTO);

    void logout();
}
