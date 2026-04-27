<template>
  <div class="login-page">
    <div class="shop-title" @mouseenter="showSurprise" @mouseleave="hideSurprise">
      <div class="title-char" :class="{ shifted: showSurpriseChar }">优</div>
      <div class="surprise-character" :class="{ visible: showSurpriseChar }">
        <span class="cowboy-emoji">🤠</span>
      </div>
      <div class="title-char" :class="{ shifted: showSurpriseChar }">鲜</div>
      <div class="title-char" :class="{ shifted: showSurpriseChar }">选</div>
    </div>
    <div class="main-content">
      <div class="left-section">
        <div class="characters-container">
          <div class="purple-character" :class="purpleClass" id="purpleCharacter">
            <div class="eyes-container">
              <div class="eye-ball" :class="{ 'peek-left': isPurplePeeking }" id="purpleLeftEye" ref="purpleLeftEye">
                <div class="pupil" id="purpleLeftPupil" ref="purpleLeftPupil"></div>
              </div>
              <div class="eye-ball" :class="{ 'peek-right': isPurplePeeking }" id="purpleRightEye" ref="purpleRightEye">
                <div class="pupil" id="purpleRightPupil" ref="purpleRightPupil"></div>
              </div>
            </div>
          </div>

          <div class="black-character" :class="blackClass" id="blackCharacter">
            <div class="eyes-container">
              <div class="eye-ball" id="blackLeftEye" ref="blackLeftEye">
                <div class="pupil" id="blackLeftPupil" ref="blackLeftPupil"></div>
              </div>
              <div class="eye-ball" id="blackRightEye" ref="blackRightEye">
                <div class="pupil" id="blackRightPupil" ref="blackRightPupil"></div>
              </div>
            </div>
          </div>

          <div class="orange-character" :class="orangeClass" id="orangeCharacter">
            <div class="eyes-container">
              <div class="pupil-only" id="orangeLeftPupil" ref="orangeLeftPupil"></div>
              <div class="pupil-only" id="orangeRightPupil" ref="orangeRightPupil"></div>
            </div>
          </div>

          <div class="yellow-character" :class="yellowClass" id="yellowCharacter">
            <div class="eyes-container">
              <div class="pupil-only" id="yellowLeftPupil" ref="yellowLeftPupil"></div>
              <div class="pupil-only" id="yellowRightPupil" ref="yellowRightPupil"></div>
            </div>
            <div class="mouth" id="yellowMouth"></div>
          </div>
        </div>
      </div>

      <div class="right-section">
      <div class="login-container">
        <div class="login-title">{{ isRegisterMode ? '用户注册' : '用户登录' }}</div>
        
        <el-form v-if="!isRegisterMode" :model="formData" :rules="loginRules" ref="loginFormRef">
          <el-form-item prop="username">
            <el-input 
              v-model="formData.username" 
              placeholder="请输入用户名"
              :prefix-icon="User"
              size="large"
            />
          </el-form-item>

          <el-form-item prop="password">
            <el-input 
              :type="showPassword ? 'text' : 'password'" 
              v-model="formData.password" 
              placeholder="请输入密码"
              :prefix-icon="Lock"
              size="large"
              @input="handlePasswordInput"
            >
              <template #suffix>
                <span class="toggle-password" @click="togglePassword">
                  {{ showPassword ? '隐藏' : '显示' }}
                </span>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item prop="role">
            <el-select v-model="formData.role" placeholder="请选择身份" size="large" style="width: 100%;">
              <el-option label="管理员" value="admin" />
              <el-option label="用户" value="user" />
            </el-select>
          </el-form-item>

          <el-form-item>
            <el-checkbox v-model="formData.remember">记住我</el-checkbox>
          </el-form-item>

          <el-form-item>
            <el-button 
              type="primary" 
              size="large" 
              class="login-btn"
              :loading="loading"
              @click="handleLogin"
            >
              登 录
            </el-button>
          </el-form-item>
        </el-form>

        <el-form v-else :model="registerFormData" :rules="registerRules" ref="registerFormRef">
          <el-form-item prop="username">
            <el-input 
              v-model="registerFormData.username" 
              placeholder="请输入用户名"
              :prefix-icon="User"
              size="large"
            />
          </el-form-item>

          <el-form-item prop="password">
            <el-input 
              :type="showPassword ? 'text' : 'password'" 
              v-model="registerFormData.password" 
              placeholder="请输入密码"
              :prefix-icon="Lock"
              size="large"
              @input="handlePasswordInput"
            >
              <template #suffix>
                <span class="toggle-password" @click="togglePassword">
                  {{ showPassword ? '隐藏' : '显示' }}
                </span>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item prop="confirmPassword">
            <el-input 
              :type="showPassword ? 'text' : 'password'" 
              v-model="registerFormData.confirmPassword" 
              placeholder="请再次输入密码"
              :prefix-icon="Lock"
              size="large"
            />
          </el-form-item>

          <el-form-item prop="phone">
            <el-input 
              v-model="registerFormData.phone" 
              placeholder="请输入电话号码"
              :prefix-icon="User"
              size="large"
            />
          </el-form-item>

          <el-form-item>
            <el-button 
              type="primary" 
              size="large" 
              class="login-btn"
              :loading="loading"
              @click="handleRegister"
            >
              注 册
            </el-button>
          </el-form-item>
        </el-form>

        <div class="mode-toggle">
          <span class="toggle-text">{{ isRegisterMode ? '已有账号？' : '没有账号？' }}</span>
          <el-button type="text" class="toggle-btn" @click="toggleMode">
            {{ isRegisterMode ? '立即登录' : '立即注册' }}
          </el-button>
        </div>
      </div>
    </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { validateUser, registerUser } from '@/data/users.js'

const router = useRouter()

const loginFormRef = ref(null)
const registerFormRef = ref(null)
const isRegisterMode = ref(false)
const formData = ref({
  username: '',
  password: '',
  role: '',
  remember: false
})
const registerFormData = ref({
  username: '',
  password: '',
  confirmPassword: '',
  phone: ''
})
const loading = ref(false)
const showPassword = ref(false)
const passwordLength = ref(0)
const isPurplePeeking = ref(false)
const showSurpriseChar = ref(false)

const validateUsername = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入用户名'))
  } else {
    callback()
  }
}

const validatePassword = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入密码'))
  } else {
    callback()
  }
}

const validateRole = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请选择身份'))
  } else {
    callback()
  }
}

const validatePhone = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入电话号码'))
  } else if (!/^1[3-9]\d{9}$/.test(value)) {
    callback(new Error('请输入正确的电话号码'))
  } else {
    callback()
  }
}

const validateConfirmPassword = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请再次输入密码'))
  } else if (value !== registerFormData.value.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const loginRules = {
  username: [
    { required: true, validator: validateUsername, trigger: 'blur' }
  ],
  password: [
    { required: true, validator: validatePassword, trigger: 'blur' }
  ],
  role: [
    { required: true, validator: validateRole, trigger: 'change' }
  ]
}

const registerRules = {
  username: [
    { required: true, validator: validateUsername, trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度在 3 到 20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, validator: validatePassword, trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度在 6 到 20 个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, validator: validateConfirmPassword, trigger: 'blur' }
  ],
  phone: [
    { required: true, validator: validatePhone, trigger: 'blur' }
  ]
}

let mouseX = ref(0)
let mouseY = ref(0)
let peekInterval = null
let blinkTimeout = null

const purpleClass = computed(() => {
  if (passwordLength.value > 0 && showPassword.value) {
    return 'password-visible'
  } else if (passwordLength.value > 0 && !showPassword.value) {
    return 'hiding-password'
  }
  return ''
})

const blackClass = computed(() => {
  if (passwordLength.value > 0 && showPassword.value) {
    return 'password-visible'
  } else if (passwordLength.value > 0 && !showPassword.value) {
    return 'hiding-password'
  }
  return ''
})

const orangeClass = computed(() => {
  return passwordLength.value > 0 && showPassword.value ? 'password-visible' : ''
})

const yellowClass = computed(() => {
  return passwordLength.value > 0 && showPassword.value ? 'password-visible' : ''
})

const handlePasswordInput = (value) => {
  passwordLength.value = value.length
  if (showPassword.value && passwordLength.value > 0) {
    startPurplePeeking()
  }
}

const togglePassword = () => {
  showPassword.value = !showPassword.value
  if (showPassword.value && passwordLength.value > 0) {
    startPurplePeeking()
  }
}

const showSurprise = () => {
  showSurpriseChar.value = true
}

const hideSurprise = () => {
  showSurpriseChar.value = false
}

const toggleMode = () => {
  isRegisterMode.value = !isRegisterMode.value
  if (loginFormRef.value) loginFormRef.value.resetFields()
  if (registerFormRef.value) registerFormRef.value.resetFields()
}

const handleLogin = async () => {
  if (!loginFormRef.value) return
  
  await loginFormRef.value.validate((valid) => {
    if (valid) {
      loading.value = true
      setTimeout(() => {
        const user = validateUser(formData.value.username, formData.value.password, formData.value.role)
        
        if (user) {
          sessionStorage.setItem('username', formData.value.username)
          sessionStorage.setItem('userRole', formData.value.role)

          if (formData.value.remember) {
            localStorage.setItem('remember', 'true')
            localStorage.setItem('rememberUsername', formData.value.username)
          } else {
            localStorage.removeItem('remember')
            localStorage.removeItem('rememberUsername')
          }

          if (formData.value.role === 'admin') {
            router.push('/dashboard')
          } else {
            router.push('/dashboard-user')
          }
          ElMessage.success('登录成功！')
        } else {
          ElMessage.error('用户名或密码错误')
        }

        loading.value = false
      }, 1000)
    }
  })
}

const handleRegister = async () => {
  if (!registerFormRef.value) return
  
  await registerFormRef.value.validate((valid) => {
    if (valid) {
      loading.value = true
      setTimeout(() => {
        const result = registerUser(
          registerFormData.value.username,
          registerFormData.value.password,
          registerFormData.value.phone
        )
        
        if (result.success) {
          ElMessage.success(result.message)
          toggleMode()
        } else {
          ElMessage.error(result.message)
        }

        loading.value = false
      }, 1000)
    }
  })
}

const updatePupils = () => {
  const refs = {
    purpleLeft: document.getElementById('purpleLeftPupil'),
    purpleRight: document.getElementById('purpleRightPupil'),
    blackLeft: document.getElementById('blackLeftPupil'),
    blackRight: document.getElementById('blackRightPupil'),
    orangeLeft: document.getElementById('orangeLeftPupil'),
    orangeRight: document.getElementById('orangeRightPupil'),
    yellowLeft: document.getElementById('yellowLeftPupil'),
    yellowRight: document.getElementById('yellowRightPupil')
  }

  const isPasswordVisible = passwordLength.value > 0 && showPassword.value

  const calculatePosition = (element, maxDistance = 10, forceX = null, forceY = null) => {
    if (!element) return { x: 0, y: 0 }
    
    if (forceX !== null && forceY !== null) {
      return { x: forceX, y: forceY }
    }

    const rect = element.getBoundingClientRect()
    const centerX = rect.left + rect.width / 2
    const centerY = rect.top + rect.height / 2

    const deltaX = mouseX.value - centerX
    const deltaY = mouseY.value - centerY
    
    const distance = Math.min(Math.sqrt(deltaX ** 2 + deltaY ** 2), maxDistance)
    const angle = Math.atan2(deltaY, deltaX)
    
    const x = Math.cos(angle) * distance
    const y = Math.sin(angle) * distance

    return { x, y }
  }

  let purpleLeftX, purpleLeftY, purpleRightX, purpleRightY

  if (isPasswordVisible) {
    if (isPurplePeeking.value) {
      purpleLeftX = 4; purpleLeftY = 5
      purpleRightX = 4; purpleRightY = 5
    } else {
      purpleLeftX = -4; purpleLeftY = -4
      purpleRightX = -4; purpleRightY = -4
    }
  }

  const purpleLeftPos = calculatePosition(refs.purpleLeft, 5, purpleLeftX, purpleLeftY)
  if (refs.purpleLeft) refs.purpleLeft.style.transform = `translate(${purpleLeftPos.x}px, ${purpleLeftPos.y}px)`
  
  const purpleRightPos = calculatePosition(refs.purpleRight, 5, purpleRightX, purpleRightY)
  if (refs.purpleRight) refs.purpleRight.style.transform = `translate(${purpleRightPos.x}px, ${purpleRightPos.y}px)`

  let blackLeftX, blackLeftY, blackRightX, blackRightY
  if (isPasswordVisible) {
    blackLeftX = -4; blackLeftY = -4
    blackRightX = -4; blackRightY = -4
  }

  const blackLeftPos = calculatePosition(refs.blackLeft, 4, blackLeftX, blackLeftY)
  if (refs.blackLeft) refs.blackLeft.style.transform = `translate(${blackLeftPos.x}px, ${blackLeftPos.y}px)`
  
  const blackRightPos = calculatePosition(refs.blackRight, 4, blackRightX, blackRightY)
  if (refs.blackRight) refs.blackRight.style.transform = `translate(${blackRightPos.x}px, ${blackRightPos.y}px)`

  let orangeLeftX, orangeLeftY, orangeRightX, orangeRightY
  let yellowLeftX, yellowLeftY, yellowRightX, yellowRightY
  
  if (isPasswordVisible) {
    orangeLeftX = orangeRightX = -5
    orangeLeftY = orangeRightY = -4
    yellowLeftX = yellowRightX = -5
    yellowLeftY = yellowRightY = -4
  }

  const orangeLeftPos = calculatePosition(refs.orangeLeft, 5, orangeLeftX, orangeLeftY)
  if (refs.orangeLeft) refs.orangeLeft.style.transform = `translate(${orangeLeftPos.x}px, ${orangeLeftPos.y}px)`
  
  const orangeRightPos = calculatePosition(refs.orangeRight, 5, orangeRightX, orangeRightY)
  if (refs.orangeRight) refs.orangeRight.style.transform = `translate(${orangeRightPos.x}px, ${orangeRightPos.y}px)`

  const yellowLeftPos = calculatePosition(refs.yellowLeft, 5, yellowLeftX, yellowLeftY)
  if (refs.yellowLeft) refs.yellowLeft.style.transform = `translate(${yellowLeftPos.x}px, ${yellowLeftPos.y}px)`
  
  const yellowRightPos = calculatePosition(refs.yellowRight, 5, yellowRightX, yellowRightY)
  if (refs.yellowRight) refs.yellowRight.style.transform = `translate(${yellowRightPos.x}px, ${yellowRightPos.y}px)`
}

const startPurplePeeking = () => {
  if (peekInterval) clearTimeout(peekInterval)
  
  const schedulePeek = () => {
    peekInterval = setTimeout(() => {
      isPurplePeeking.value = true
      updatePupils()

      setTimeout(() => {
        isPurplePeeking.value = false
        updatePupils()
        if (showPassword.value && passwordLength.value > 0) {
          schedulePeek()
        }
      }, 800)
    }, Math.random() * 3000 + 2000)
  }

  if (showPassword.value && passwordLength.value > 0) {
    schedulePeek()
  }
}

const purpleLeftEye = ref(null)
const purpleRightEye = ref(null)
const blackLeftEye = ref(null)
const blackRightEye = ref(null)

const blink = () => {
  const eyes = [
    purpleLeftEye.value,
    purpleRightEye.value,
    blackLeftEye.value,
    blackRightEye.value
  ].filter(Boolean)
  
  eyes.forEach(eye => {
    eye.style.height = '2px'
    setTimeout(() => {
      eye.style.height = ''
    }, 150)
  })
  
  blinkTimeout = setTimeout(blink, Math.random() * 6000 + 5000)
}

const handleMouseMove = (e) => {
  mouseX.value = e.clientX
  mouseY.value = e.clientY
  updatePupils()
}

onMounted(() => {
  document.addEventListener('mousemove', handleMouseMove)
  
  setTimeout(blink, 3000)
  
  const savedUsername = localStorage.getItem('rememberUsername')
  if (savedUsername) {
    formData.value.username = savedUsername
    formData.value.remember = true
  }
})

onUnmounted(() => {
  document.removeEventListener('mousemove', handleMouseMove)
  if (peekInterval) clearTimeout(peekInterval)
  if (blinkTimeout) clearTimeout(blinkTimeout)
})
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.shop-title {
  font-size: 72px;
  font-weight: bold;
  color: white;
  text-align: center;
  padding: 40px 0 20px;
  position: relative;
  z-index: 10;
  cursor: default;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0px;
}

.title-char {
  display: inline-block;
  transition: transform 0.5s cubic-bezier(0.68, -0.55, 0.265, 1.55);
  text-shadow: 
    0 1px 0 #5a4fa0,
    0 2px 0 #524795,
    0 3px 0 #4a3f8a,
    0 4px 0 #423780,
    0 5px 0 #3a2f75,
    0 6px 0 #32276b,
    0 7px 0 #2a1f60,
    0 8px 0 #221755,
    0 9px 0 #1a0f4b,
    0 10px 0 #120740,
    0 20px 35px rgba(0, 0, 0, 0.4),
    0 0 20px rgba(255, 255, 255, 0.15);
}

.title-char.shifted:nth-child(1) {
  transform: translateX(-80px);
}

.title-char.shifted:nth-child(3) {
  transform: translateX(80px);
}

.title-char.shifted:nth-child(4) {
  transform: translateX(80px);
}

.surprise-character {
  position: absolute;
  width: 100px;
  height: 100px;
  background-color: transparent;
  border-radius: 50%;
  left: 50%;
  top: 50%;
  margin-left: -40px;
  margin-top: 0px;
  transform: translate(-50%, -50%) scale(0);
  opacity: 0;
  transition: all 0.4s cubic-bezier(0.68, -0.55, 0.265, 1.55);
  z-index: 20;
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.3);
  pointer-events: none;
  display: flex;
  align-items: center;
  justify-content: center;
}

.surprise-character.visible {
  transform: translate(-50%, -50%) scale(1);
  opacity: 1;
}

.cowboy-emoji {
  font-size: 80px;
  line-height: 1;
  animation: bounce 0.4s cubic-bezier(0.68, -0.55, 0.265, 1.55);
}

@keyframes bounce {
  0%, 100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.2) rotate(-5deg);
  }
}

.main-content {
  flex: 1;
  display: flex;
}

.left-section {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 40px;
}

.right-section {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 40px;
}

.characters-container {
  position: relative;
  width: 550px;
  height: 400px;
}

.purple-character {
  position: absolute;
  bottom: 0;
  left: 70px;
  width: 180px;
  height: 400px;
  background-color: #6C3FF5;
  border-radius: 10px 10px 0 0;
  z-index: 1;
  transition: all 0.7s ease-in-out;
  transform-origin: bottom center;
}

.purple-character.password-visible {
  height: 400px;
  transform: skewX(0deg);
}

.purple-character.hiding-password {
  height: 440px;
  transform: skewX(-12deg) translateX(40px);
}

.black-character {
  position: absolute;
  bottom: 0;
  left: 240px;
  width: 120px;
  height: 310px;
  background-color: #2D2D2D;
  border-radius: 8px 8px 0 0;
  z-index: 2;
  transition: all 0.7s ease-in-out;
  transform-origin: bottom center;
}

.black-character.password-visible {
  transform: skewX(0deg);
}

.black-character.hiding-password {
  transform: skewX(-15deg) translateX(30px);
}

.orange-character {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 240px;
  height: 200px;
  background-color: #FF9B6B;
  border-radius: 120px 120px 0 0;
  z-index: 3;
  transition: all 0.7s ease-in-out;
  transform-origin: bottom center;
}

.orange-character.password-visible {
  transform: skewX(0deg);
}

.yellow-character {
  position: absolute;
  bottom: 0;
  left: 310px;
  width: 140px;
  height: 230px;
  background-color: #E8D754;
  border-radius: 70px 70px 0 0;
  z-index: 4;
  transition: all 0.7s ease-in-out;
  transform-origin: bottom center;
}

.yellow-character.password-visible {
  transform: skewX(0deg);
}

.eyes-container {
  position: absolute;
  display: flex;
  gap: 8px;
  transition: all 0.7s ease-in-out;
}

.eye-ball {
  width: 48px;
  height: 48px;
  background-color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  transition: all 0.15s ease-out;
}

.pupil {
  width: 16px;
  height: 16px;
  background-color: #2D2D2D;
  border-radius: 50%;
  transition: transform 0.1s ease-out;
}

.pupil-only {
  width: 12px;
  height: 12px;
  background-color: #2D2D2D;
  border-radius: 50%;
  transition: transform 0.1s ease-out;
}

.mouth {
  position: absolute;
  width: 80px;
  height: 4px;
  background-color: #2D2D2D;
  border-radius: 2px;
  transition: all 0.2s ease-out;
}

.purple-character .eyes-container {
  left: 45px;
  top: 40px;
  gap: 32px;
}

.purple-character.password-visible .eyes-container {
  left: 20px;
  top: 35px;
}

.purple-character.hiding-password .eyes-container {
  left: 55px;
  top: 65px;
}

.purple-character .eye-ball {
  width: 18px;
  height: 18px;
}

.purple-character .pupil {
  width: 7px;
  height: 7px;
}

.purple-character .eye-ball.peek-left {
  width: 20px;
  height: 20px;
}

.purple-character .eye-ball.peek-right {
  width: 14px;
  height: 14px;
}

.black-character .eyes-container {
  left: 26px;
  top: 32px;
  gap: 24px;
}

.black-character.password-visible .eyes-container {
  left: 10px;
  top: 28px;
}

.black-character.hiding-password .eyes-container {
  left: 32px;
  top: 12px;
}

.black-character .eye-ball {
  width: 16px;
  height: 16px;
}

.black-character .pupil {
  width: 6px;
  height: 6px;
}

.orange-character .eyes-container {
  left: 82px;
  top: 90px;
  gap: 32px;
}

.orange-character.password-visible .eyes-container {
  left: 50px;
  top: 85px;
}

.yellow-character .eyes-container {
  left: 52px;
  top: 40px;
  gap: 24px;
}

.yellow-character.password-visible .eyes-container {
  left: 20px;
  top: 35px;
}

.yellow-character .mouth {
  left: 40px;
  top: 88px;
}

.yellow-character.password-visible .mouth {
  left: 10px;
  top: 88px;
}

.login-container {
  background: white;
  padding: 40px;
  border-radius: 20px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  width: 400px;
}

.login-title {
  text-align: center;
  color: #333;
  margin-bottom: 30px;
  font-size: 24px;
  font-weight: bold;
}

.toggle-password {
  cursor: pointer;
  font-size: 14px;
  color: #667eea;
  font-weight: bold;
  user-select: none;
  padding: 0 8px;
}

.toggle-password:hover {
  color: #5a6fd6;
}

.login-btn {
  width: 100%;
  font-size: 16px;
  font-weight: bold;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  border-radius: 12px;
  color: white;
  cursor: pointer;
  position: relative;
  transition: all 0.15s ease;
  box-shadow: 
    0 6px 0 #4a3f8a,
    0 8px 10px rgba(0, 0, 0, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.2);
}

.login-btn:hover {
  transform: translateY(-2px);
  box-shadow: 
    0 8px 0 #4a3f8a,
    0 12px 15px rgba(0, 0, 0, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.2);
}

.login-btn:active {
  transform: translateY(4px);
  box-shadow: 
    0 2px 0 #4a3f8a,
    0 3px 5px rgba(0, 0, 0, 0.2),
    inset 0 2px 5px rgba(0, 0, 0, 0.2);
}

.login-btn:focus {
  outline: none;
}

.login-btn:focus-visible {
  box-shadow: 
    0 6px 0 #4a3f8a,
    0 8px 10px rgba(0, 0, 0, 0.3),
    inset 0 1px 0 rgba(255, 255, 255, 0.2),
    0 0 0 3px rgba(102, 126, 234, 0.5);
}

:deep(.el-input__wrapper) {
  border-radius: 10px;
  padding: 4px 15px;
}

:deep(.el-input__wrapper:focus-within) {
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.3);
}

.mode-toggle {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #f0f0f0;
}

.toggle-text {
  color: #606266;
  font-size: 14px;
}

.toggle-btn {
  color: #667eea;
  font-weight: bold;
  font-size: 14px;
  padding: 0;
}

.toggle-btn:hover {
  color: #5a6fd6;
}
</style>
