import api from './index'

const LOGIN_API_URL = '/user/login'
const REGISTER_API_URL = '/user/register'

export const authApi = {
  login(username, password) {
    return api.post(LOGIN_API_URL, { username, password })
  },

  register(username, password, confirmPassword) {
    return api.post(REGISTER_API_URL, { username, password, confirmPassword })
  },

  logout() {
    return api.post('/user/logout')
  },

  getCurrentUser() {
    return api.get('/user/current')
  }
}

export default authApi
