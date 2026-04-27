<template>
  <div class="auth-container">
    <div class="auth-card">
      <div class="auth-header">
        <div class="logo">
          <span class="logo-icon">📚</span>
        </div>
        <h1 class="auth-title">Personal Note RAG</h1>
        <p class="auth-subtitle">基于 LangChain4j 的个人笔记知识库问答系统</p>
      </div>

      <!-- 登录表单 -->
      <form v-if="!isRegisterMode" @submit.prevent="handleLoginSubmit" class="auth-form">
        <div class="form-group">
          <label for="username" class="form-label">用户名</label>
          <input
            id="username"
            v-model="loginForm.username"
            type="text"
            class="form-input"
            placeholder="请输入用户名"
            :disabled="loading"
            autocomplete="username"
          />
        </div>

        <div class="form-group">
          <label for="password" class="form-label">密码</label>
          <div class="password-wrapper">
            <input
              id="password"
              v-model="loginForm.password"
              :type="showPassword ? 'text' : 'password'"
              class="form-input"
              placeholder="请输入密码"
              :disabled="loading"
              autocomplete="current-password"
            />
            <button
              type="button"
              class="toggle-password"
              @click="showPassword = !showPassword"
              tabindex="-1"
            >
              {{ showPassword ? '🙈' : '👁️' }}
            </button>
          </div>
        </div>

        <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
        <div v-if="successMessage" class="success-message">{{ successMessage }}</div>

        <button type="submit" class="submit-btn" :disabled="loading || !isLoginFormValid">
          <span v-if="loading" class="loading-spinner"></span>
          {{ loading ? '登录中...' : '登 录' }}
        </button>
      </form>

      <!-- 注册表单 -->
      <form v-else @submit.prevent="handleRegisterSubmit" class="auth-form">
        <div class="form-group">
          <label for="reg-username" class="form-label">用户名</label>
          <input
            id="reg-username"
            v-model="registerForm.username"
            type="text"
            class="form-input"
            placeholder="请输入用户名（3-32位字母、数字或下划线）"
            :disabled="loading"
            autocomplete="username"
          />
          <p v-if="usernameError" class="field-error">{{ usernameError }}</p>
        </div>

        <div class="form-group">
          <label for="reg-password" class="form-label">密码</label>
          <div class="password-wrapper">
            <input
              id="reg-password"
              v-model="registerForm.password"
              :type="showPassword ? 'text' : 'password'"
              class="form-input"
              placeholder="请输入密码（至少6位）"
              :disabled="loading"
              autocomplete="new-password"
            />
            <button
              type="button"
              class="toggle-password"
              @click="showPassword = !showPassword"
              tabindex="-1"
            >
              {{ showPassword ? '🙈' : '👁️' }}
            </button>
          </div>
          <p v-if="passwordError" class="field-error">{{ passwordError }}</p>
        </div>

        <div class="form-group">
          <label for="confirmPassword" class="form-label">确认密码</label>
          <div class="password-wrapper">
            <input
              id="confirmPassword"
              v-model="registerForm.confirmPassword"
              :type="showConfirmPassword ? 'text' : 'password'"
              class="form-input"
              placeholder="请再次输入密码"
              :disabled="loading"
              autocomplete="new-password"
            />
            <button
              type="button"
              class="toggle-password"
              @click="showConfirmPassword = !showConfirmPassword"
              tabindex="-1"
            >
              {{ showConfirmPassword ? '🙈' : '👁️' }}
            </button>
          </div>
          <p v-if="confirmPasswordError" class="field-error">{{ confirmPasswordError }}</p>
        </div>

        <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
        <div v-if="successMessage" class="success-message">{{ successMessage }}</div>

        <button type="submit" class="submit-btn" :disabled="loading || !isRegisterFormValid">
          <span v-if="loading" class="loading-spinner"></span>
          {{ loading ? '注册中...' : '注 册' }}
        </button>
      </form>

      <div class="auth-footer">
        <p>
          {{ isRegisterMode ? '已有账户？' : '还没有账户？' }}
          <button type="button" class="link-btn" @click="toggleMode">
            {{ isRegisterMode ? '立即登录' : '立即注册' }}
          </button>
        </p>
      </div>
    </div>

    <div class="background-decoration">
      <div class="circle circle-1"></div>
      <div class="circle circle-2"></div>
      <div class="circle circle-3"></div>
    </div>
  </div>
</template>

<script>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { authStore } from '@/utils/auth'

export default {
  name: 'LoginView',
  setup() {
    const router = useRouter()

    const isRegisterMode = ref(false)
    const loading = ref(false)
    const errorMessage = ref('')
    const successMessage = ref('')
    const showPassword = ref(false)
    const showConfirmPassword = ref(false)

    // 登录表单
    const loginForm = ref({
      username: '',
      password: ''
    })

    // 注册表单
    const registerForm = ref({
      username: '',
      password: '',
      confirmPassword: ''
    })

    const usernameError = ref('')
    const passwordError = ref('')
    const confirmPasswordError = ref('')

    const isLoginFormValid = computed(() => {
      return loginForm.value.username.trim() !== '' &&
             loginForm.value.password.trim() !== ''
    })

    const isRegisterFormValid = computed(() => {
      return registerForm.value.username.trim() !== '' &&
             registerForm.value.password.trim() !== '' &&
             registerForm.value.confirmPassword.trim() !== '' &&
             !usernameError.value &&
             !passwordError.value &&
             !confirmPasswordError.value
    })

    const toggleMode = () => {
      isRegisterMode.value = !isRegisterMode.value
      errorMessage.value = ''
      successMessage.value = ''
      showPassword.value = false
      showConfirmPassword.value = false
      usernameError.value = ''
      passwordError.value = ''
      confirmPasswordError.value = ''
      if (!isRegisterMode.value) {
        registerForm.value = { username: '', password: '', confirmPassword: '' }
      } else {
        loginForm.value = { username: '', password: '' }
      }
    }

    const handleLoginSubmit = async () => {
      if (!isLoginFormValid.value || loading.value) return

      loading.value = true
      errorMessage.value = ''

      try {
        const response = await authApi.login(
          loginForm.value.username,
          loginForm.value.password
        )

        const data = response.data

        if (data.code === 1 && data.data) {
          const { token, id, username } = data.data
          authStore.setToken(token)
          authStore.setUser({ id, username })
          router.push('/chat')
        } else {
          errorMessage.value = data.msg || '登录失败，请检查用户名和密码'
        }
      } catch (error) {
        console.error('Login error:', error)
        if (error.response) {
          const status = error.response.status
          if (status === 401) {
            errorMessage.value = '用户名或密码错误'
          } else if (status === 403) {
            errorMessage.value = '账户已被禁用，请联系管理员'
          } else {
            errorMessage.value = error.response.data?.msg || '登录失败，请稍后重试'
          }
        } else if (error.message && error.message.includes('timeout')) {
          errorMessage.value = '请求超时，请检查网络连接'
        } else {
          errorMessage.value = error.message || '网络错误，请稍后重试'
        }
      } finally {
        loading.value = false
      }
    }

    const validateUsername = () => {
      const username = registerForm.value.username.trim()
      if (!username) {
        usernameError.value = '用户名不能为空'
        return false
      }
      if (username.length < 3 || username.length > 32) {
        usernameError.value = '用户名长度需在3-32个字符之间'
        return false
      }
      if (!/^[a-zA-Z0-9_]+$/.test(username)) {
        usernameError.value = '用户名只能包含字母、数字和下划线'
        return false
      }
      usernameError.value = ''
      return true
    }

    const validatePassword = () => {
      const password = registerForm.value.password
      if (!password) {
        passwordError.value = '密码不能为空'
        return false
      }
      if (password.length < 6 || password.length > 64) {
        passwordError.value = '密码长度需在6-64个字符之间'
        return false
      }
      passwordError.value = ''
      return true
    }

    const validateConfirmPassword = () => {
      const confirmPassword = registerForm.value.confirmPassword
      if (!confirmPassword) {
        confirmPasswordError.value = '确认密码不能为空'
        return false
      }
      if (confirmPassword !== registerForm.value.password) {
        confirmPasswordError.value = '两次输入的密码不一致'
        return false
      }
      confirmPasswordError.value = ''
      return true
    }

    const handleRegisterSubmit = async () => {
      if (!isRegisterFormValid.value || loading.value) return

      const isUsernameValid = validateUsername()
      const isPasswordValid = validatePassword()
      const isConfirmPasswordValid = validateConfirmPassword()

      if (!isUsernameValid || !isPasswordValid || !isConfirmPasswordValid) {
        return
      }

      loading.value = true
      errorMessage.value = ''
      successMessage.value = ''

      try {
        const response = await authApi.register(
          registerForm.value.username,
          registerForm.value.password,
          registerForm.value.confirmPassword
        )

        const data = response.data

        if (data.code === 1 && data.data) {
          successMessage.value = '注册成功！请登录'
          setTimeout(() => {
            toggleMode()
            loginForm.value.username = registerForm.value.username
            loginForm.value.password = ''
          }, 1500)
        } else {
          errorMessage.value = data.msg || '注册失败，请稍后重试'
        }
      } catch (error) {
        console.error('Register error:', error)
        if (error.response) {
          errorMessage.value = error.response.data?.msg || '注册失败，请稍后重试'
        } else if (error.message && error.message.includes('timeout')) {
          errorMessage.value = '请求超时，请检查网络连接'
        } else {
          errorMessage.value = error.message || '网络错误，请稍后重试'
        }
      } finally {
        loading.value = false
      }
    }

    return {
      isRegisterMode,
      loading,
      errorMessage,
      successMessage,
      showPassword,
      showConfirmPassword,
      loginForm,
      registerForm,
      usernameError,
      passwordError,
      confirmPasswordError,
      isLoginFormValid,
      isRegisterFormValid,
      toggleMode,
      handleLoginSubmit,
      handleRegisterSubmit
    }
  }
}
</script>

<style scoped>
.auth-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  position: relative;
  overflow: hidden;
}

.background-decoration {
  position: absolute;
  width: 100%;
  height: 100%;
  top: 0;
  left: 0;
  pointer-events: none;
}

.circle {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
}

.circle-1 {
  width: 400px;
  height: 400px;
  top: -200px;
  right: -100px;
  animation: float 6s ease-in-out infinite;
}

.circle-2 {
  width: 300px;
  height: 300px;
  bottom: -150px;
  left: -100px;
  animation: float 8s ease-in-out infinite reverse;
}

.circle-3 {
  width: 200px;
  height: 200px;
  top: 50%;
  left: 10%;
  animation: float 10s ease-in-out infinite;
}

@keyframes float {
  0%, 100% { transform: translateY(0px); }
  50% { transform: translateY(-20px); }
}

.auth-card {
  background: white;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  padding: 48px 40px;
  width: 100%;
  max-width: 420px;
  position: relative;
  z-index: 1;
  animation: slideUp 0.5s ease-out;
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(30px); }
  to { opacity: 1; transform: translateY(0); }
}

.auth-header {
  text-align: center;
  margin-bottom: 32px;
}

.logo {
  margin-bottom: 16px;
}

.logo-icon {
  font-size: 56px;
  display: inline-block;
  animation: bounce 2s infinite;
}

@keyframes bounce {
  0%, 20%, 50%, 80%, 100% { transform: translateY(0); }
  40% { transform: translateY(-10px); }
  60% { transform: translateY(-5px); }
}

.auth-title {
  color: #1a73e8;
  font-size: 28px;
  font-weight: 600;
  margin-bottom: 8px;
}

.auth-subtitle {
  color: #666;
  font-size: 14px;
  line-height: 1.5;
}

.auth-form {
  margin-bottom: 24px;
}

.form-group {
  margin-bottom: 20px;
}

.form-label {
  display: block;
  color: #333;
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
}

.form-input {
  width: 100%;
  padding: 12px 16px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  font-size: 15px;
  transition: all 0.3s ease;
  outline: none;
}

.form-input:focus {
  border-color: #1a73e8;
  box-shadow: 0 0 0 3px rgba(26, 115, 232, 0.1);
}

.form-input:disabled {
  background: #f5f5f5;
  cursor: not-allowed;
}

.password-wrapper {
  position: relative;
}

.toggle-password {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  cursor: pointer;
  font-size: 18px;
  padding: 4px;
  line-height: 1;
}

.field-error {
  color: #d32f2f;
  font-size: 12px;
  margin-top: 4px;
  margin-left: 4px;
}

.error-message {
  background: #fff3f3;
  color: #d32f2f;
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 14px;
  margin-bottom: 16px;
  border-left: 4px solid #d32f2f;
  animation: shake 0.4s ease-in-out;
}

.success-message {
  background: #e8f5e9;
  color: #2e7d32;
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 14px;
  margin-bottom: 16px;
  border-left: 4px solid #2e7d32;
}

@keyframes shake {
  0%, 100% { transform: translateX(0); }
  25% { transform: translateX(-5px); }
  75% { transform: translateX(5px); }
}

.submit-btn {
  width: 100%;
  padding: 14px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.submit-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(102, 126, 234, 0.4);
}

.submit-btn:active:not(:disabled) {
  transform: translateY(0);
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.loading-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.auth-footer {
  text-align: center;
  color: #999;
  font-size: 13px;
  padding-top: 20px;
  border-top: 1px solid #eee;
}

.link-btn {
  color: #1a73e8;
  background: none;
  border: none;
  font-weight: 500;
  cursor: pointer;
  font-size: 13px;
  padding: 0;
}

.link-btn:hover {
  text-decoration: underline;
}

@media (max-width: 480px) {
  .auth-card {
    margin: 20px;
    padding: 32px 24px;
  }

  .auth-title {
    font-size: 24px;
  }

  .logo-icon {
    font-size: 48px;
  }
}

@media (max-width: 360px) {
  .auth-card {
    margin: 12px;
    padding: 24px 20px;
  }
}
</style>
