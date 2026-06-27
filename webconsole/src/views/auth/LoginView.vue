<template>
  <div class="auth-page">
    <!-- 左侧：品牌叙事区 -->
    <aside class="brand-panel">
      <svg class="brand-stars" aria-hidden="true" viewBox="0 0 400 600" preserveAspectRatio="xMidYMid slice">
        <g class="star-group">
          <circle cx="60" cy="80" r="1.2" />
          <circle cx="320" cy="120" r="0.8" />
          <circle cx="180" cy="60" r="1.5" />
          <circle cx="340" cy="260" r="1" />
          <circle cx="90" cy="220" r="0.9" />
          <circle cx="260" cy="180" r="1.3" />
          <circle cx="140" cy="320" r="0.7" />
          <circle cx="300" cy="380" r="1.1" />
          <circle cx="50" cy="420" r="1" />
          <circle cx="220" cy="460" r="0.8" />
          <circle cx="360" cy="500" r="1.2" />
          <circle cx="100" cy="540" r="0.9" />
          <line x1="180" y1="60" x2="260" y2="180" />
          <line x1="260" y1="180" x2="340" y2="260" />
          <line x1="90" y1="220" x2="180" y2="60" />
          <line x1="140" y1="320" x2="220" y2="460" />
        </g>
      </svg>
      <div class="brand-content">
        <div class="brand-logo">
          <svg viewBox="0 0 48 48" width="44" height="44" aria-hidden="true">
            <path
              d="M24 3 L27.5 20.5 L45 24 L27.5 27.5 L24 45 L20.5 27.5 L3 24 L20.5 20.5 Z"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linejoin="round"
            />
          </svg>
        </div>
        <h1 class="brand-name">临星</h1>
        <p class="brand-tagline">让每一份笔记<br />成为思考的星图</p>
        <div class="brand-foot">
          <span class="brand-line"></span>
          <span class="brand-meta">个人学习平台</span>
        </div>
      </div>
    </aside>

    <!-- 右侧：登录 / 注册主区 -->
    <main class="auth-main">
      <div class="auth-card">
        <header class="auth-header">
          <h2 class="auth-title">{{ isRegisterMode ? '创建账户' : '欢迎回来' }}</h2>
          <p class="auth-hint">{{ isRegisterMode ? '开始记录你的学习' : '登录以继续' }}</p>
        </header>

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
                <el-icon><component :is="showPassword ? 'Hide' : 'View'" /></el-icon>
              </button>
            </div>
          </div>

          <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
          <div v-if="successMessage" class="success-message">{{ successMessage }}</div>

          <button type="submit" class="submit-btn" :disabled="loading || !isLoginFormValid">
            <span v-if="loading" class="loading-spinner"></span>
            <span class="btn-text">{{ loading ? '登录中...' : '登 录' }}</span>
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
              placeholder="3-32 位字母、数字或下划线"
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
                placeholder="至少 6 位"
                :disabled="loading"
                autocomplete="new-password"
              />
              <button
                type="button"
                class="toggle-password"
                @click="showPassword = !showPassword"
                tabindex="-1"
              >
                <el-icon><component :is="showPassword ? 'Hide' : 'View'" /></el-icon>
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
                <el-icon><component :is="showConfirmPassword ? 'Hide' : 'View'" /></el-icon>
              </button>
            </div>
            <p v-if="confirmPasswordError" class="field-error">{{ confirmPasswordError }}</p>
          </div>

          <div v-if="errorMessage" class="error-message">{{ errorMessage }}</div>
          <div v-if="successMessage" class="success-message">{{ successMessage }}</div>

          <button type="submit" class="submit-btn" :disabled="loading || !isRegisterFormValid">
            <span v-if="loading" class="loading-spinner"></span>
            <span class="btn-text">{{ loading ? '注册中...' : '注 册' }}</span>
          </button>
        </form>

        <footer class="auth-footer">
          <span class="footer-text">{{ isRegisterMode ? '已有账户？' : '还没有账户？' }}</span>
          <button type="button" class="link-btn" @click="toggleMode">
            {{ isRegisterMode ? '立即登录' : '立即注册' }}
          </button>
        </footer>
      </div>
    </main>
  </div>
</template>

<script>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { authStore } from '@/stores/authStore'

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
/* 设计 token：深墨绿 + 暖米白 + 琥珀强调，彻底告别紫粉渐变 */
.auth-page {
  --ink: #1a2e2a;
  --ink-soft: #4a5a55;
  --ink-mute: #8a948f;
  --paper: #faf8f4;
  --paper-2: #f1ece3;
  --line: #d9d2c4;
  --line-soft: #e8e2d4;
  --accent: #b8763d;
  --accent-hover: #a0682f;
  --accent-soft: #f3e6d4;
  --brand-bg: #1a3a32;
  --brand-bg-2: #102822;
  --brand-fg: #e8e0d0;
  --brand-fg-mute: rgba(232, 224, 208, 0.55);
  --danger: #b03a2e;
  --danger-bg: #f9ece9;
  --danger-border: #e8c9c1;
  --success: #4a6b3a;
  --success-bg: #edf2e6;
  --success-border: #d4dec8;

  --font-serif: 'Songti SC', 'STSong', 'Source Han Serif SC', 'Noto Serif CJK SC', 'SimSun', serif;
  --font-sans: 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Segoe UI', sans-serif;

  min-height: 100vh;
  display: grid;
  grid-template-columns: 5fr 7fr;
  background: var(--paper);
  color: var(--ink);
  font-family: var(--font-sans);
}

/* —— 左侧品牌叙事区 —— */
.brand-panel {
  position: relative;
  background: var(--brand-bg);
  color: var(--brand-fg);
  overflow: hidden;
  display: flex;
  align-items: flex-end;
  padding: 56px 48px;
}

.brand-panel::before {
  /* 右下角的深色渐晕，营造空间纵深 */
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at 30% 110%, var(--brand-bg-2) 0%, transparent 55%);
  pointer-events: none;
}

.brand-stars {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0.4;
}

.brand-stars .star-group circle {
  fill: var(--brand-fg);
}

.brand-stars .star-group line {
  stroke: var(--brand-fg);
  stroke-width: 0.5;
  opacity: 0.35;
}

.brand-content {
  position: relative;
  z-index: 1;
  max-width: 360px;
}

.brand-logo {
  color: var(--brand-fg);
  margin-bottom: 28px;
  opacity: 0.92;
}

.brand-name {
  font-family: var(--font-serif);
  font-size: 56px;
  font-weight: 600;
  letter-spacing: 8px;
  line-height: 1;
  margin: 0 0 22px 0;
  color: var(--brand-fg);
}

.brand-tagline {
  font-family: var(--font-serif);
  font-size: 17px;
  line-height: 1.75;
  color: var(--brand-fg-mute);
  margin: 0 0 56px 0;
  letter-spacing: 1px;
}

.brand-foot {
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand-line {
  display: block;
  width: 32px;
  height: 1px;
  background: var(--brand-fg-mute);
}

.brand-meta {
  font-size: 11px;
  letter-spacing: 4px;
  color: var(--brand-fg-mute);
  text-transform: uppercase;
}

/* —— 右侧登录主区 —— */
.auth-main {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 32px;
  background: var(--paper);
}

.auth-card {
  width: 100%;
  max-width: 380px;
  animation: card-in 0.5s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes card-in {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

.auth-header {
  margin-bottom: 40px;
}

.auth-title {
  font-family: var(--font-serif);
  font-size: 30px;
  font-weight: 600;
  color: var(--ink);
  letter-spacing: 2px;
  margin: 0 0 10px 0;
  line-height: 1.2;
}

.auth-hint {
  font-size: 14px;
  color: var(--ink-mute);
  margin: 0;
  letter-spacing: 0.5px;
}

.auth-form {
  margin-bottom: 32px;
}

.form-group {
  margin-bottom: 22px;
}

.form-label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: var(--ink-soft);
  margin-bottom: 10px;
  letter-spacing: 2px;
}

.form-input {
  width: 100%;
  padding: 11px 0 11px 0;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  font-size: 15px;
  font-family: var(--font-sans);
  color: var(--ink);
  outline: none;
  transition: border-color 0.25s ease, padding 0.25s ease;
  border-radius: 0;
}

.form-input::placeholder {
  color: var(--ink-mute);
  font-size: 14px;
  opacity: 0.7;
}

.form-input:focus {
  border-bottom-color: var(--accent);
}

.form-input:disabled {
  color: var(--ink-mute);
  cursor: not-allowed;
  opacity: 0.6;
}

.password-wrapper {
  position: relative;
}

.password-wrapper .form-input {
  padding-right: 32px;
}

.toggle-password {
  position: absolute;
  right: 0;
  bottom: 10px;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 16px;
  padding: 4px;
  line-height: 1;
  opacity: 0.5;
  transition: opacity 0.2s;
}

.toggle-password:hover {
  opacity: 0.85;
}

.field-error {
  color: var(--danger);
  font-size: 12px;
  margin-top: 8px;
  letter-spacing: 0.3px;
}

.error-message {
  background: var(--danger-bg);
  color: var(--danger);
  padding: 11px 14px;
  border-radius: 4px;
  font-size: 13px;
  margin-bottom: 18px;
  border-left: 2px solid var(--danger);
}

.success-message {
  background: var(--success-bg);
  color: var(--success);
  padding: 11px 14px;
  border-radius: 4px;
  font-size: 13px;
  margin-bottom: 18px;
  border-left: 2px solid var(--success);
}

.submit-btn {
  width: 100%;
  padding: 14px;
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: 4px;
  font-size: 15px;
  font-weight: 500;
  font-family: var(--font-sans);
  letter-spacing: 6px;
  cursor: pointer;
  transition: background 0.25s ease, transform 0.15s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 8px;
}

.submit-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}

.submit-btn:active:not(:disabled) {
  transform: translateY(1px);
}

.submit-btn:disabled {
  background: var(--paper-2);
  color: var(--ink-mute);
  cursor: not-allowed;
}

.loading-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.auth-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 28px;
  border-top: 1px solid var(--line-soft);
}

.footer-text {
  font-size: 13px;
  color: var(--ink-mute);
}

.link-btn {
  color: var(--accent);
  background: none;
  border: none;
  font-weight: 500;
  cursor: pointer;
  font-size: 13px;
  padding: 0;
  font-family: var(--font-sans);
  transition: color 0.2s;
}

.link-btn:hover {
  color: var(--accent-hover);
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* —— 响应式：窄屏隐藏品牌区 —— */
@media (max-width: 860px) {
  .auth-page {
    grid-template-columns: 1fr;
  }

  .brand-panel {
    display: none;
  }

  .auth-main {
    padding: 32px 24px;
  }
}

@media (max-width: 480px) {
  .auth-main {
    padding: 24px 20px;
    align-items: flex-start;
  }

  .auth-card {
    margin-top: 40px;
  }

  .auth-title {
    font-size: 26px;
  }
}
</style>
