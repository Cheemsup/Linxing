import { reactive } from 'vue'

const TOKEN_KEY = 'linxing_token'
const USER_KEY = 'linxing_user'

const state = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  user: JSON.parse(localStorage.getItem(USER_KEY) || 'null'),
  isLoggedIn: !!localStorage.getItem(TOKEN_KEY)
})

export const authStore = {
  state,

  setToken(token) {
    state.token = token
    state.isLoggedIn = !!token
    localStorage.setItem(TOKEN_KEY, token)
  },

  setUser(user) {
    state.user = user
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },

  getUser() {
    return state.user
  },

  getUserId() {
    return state.user?.id
  },

  getUsername() {
    return state.user?.username
  },

  getToken() {
    return state.token
  },

  clearAuth() {
    state.token = ''
    state.user = null
    state.isLoggedIn = false
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  },

  isAuthenticated() {
    return state.isLoggedIn && !!state.token
  }
}

export default authStore
