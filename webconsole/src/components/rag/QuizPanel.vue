<template>
  <div class="quiz-panel">
    <!-- 顶部操作栏 -->
    <div class="quiz-toolbar">
      <h2 class="quiz-title">{{ quizData.title || '知识测验' }}</h2>
      <div class="quiz-actions">
        <button class="btn btn-primary" @click="handleSubmit" :disabled="submitted">
          {{ submitted ? '已提交' : '提交答案' }}
        </button>
        <button class="btn btn-secondary" @click="handleSaveDraft" :disabled="submitted" v-if="!submitted">
          保存进度
        </button>
      </div>
    </div>

    <!-- 得分展示 -->
    <div v-if="submitted && submitResult" class="score-bar" :class="scoreClass">
      <el-icon class="score-icon"><component :is="scoreIcon" /></el-icon>
      <span class="score-text">得分：{{ submitResult.score }} / {{ submitResult.total }}</span>
      <span class="score-pct">（{{ scorePct }}%）</span>
    </div>

    <!-- 题目列表 -->
    <div class="questions-list">
      <div
        v-for="(q, idx) in quizData.questions"
        :key="q.id"
        class="question-card"
        :class="{ 'question-correct': submitted && isCorrect(q.id), 'question-wrong': submitted && !isCorrect(q.id) }"
      >
        <div class="question-header">
          <span class="question-number">第{{ idx + 1 }}题</span>
          <span class="question-type-badge" :class="'type-' + q.type">{{ typeLabel(q.type) }}</span>
        </div>

        <div class="question-stem">{{ q.stem }}</div>

        <!-- 单选题 -->
        <div v-if="q.type === 'single_choice'" class="options-group">
          <label
            v-for="opt in q.options"
            :key="opt"
            class="option-item"
            :class="{
              'option-selected': answers[q.id] === opt,
              'option-correct': submitted && opt === correctAnswer(q.id),
              'option-wrong': submitted && answers[q.id] === opt && opt !== correctAnswer(q.id)
            }"
          >
            <input
              type="radio"
              :name="'q_' + q.id"
              :value="opt"
              :checked="answers[q.id] === opt"
              :disabled="submitted"
              @change="setAnswer(q.id, opt)"
            />
            <span class="option-text">{{ opt }}</span>
          </label>
        </div>

        <!-- 多选题 -->
        <div v-else-if="q.type === 'multi_choice'" class="options-group">
          <label
            v-for="opt in q.options"
            :key="opt"
            class="option-item"
            :class="{
              'option-selected': (answers[q.id] || []).includes(opt),
              'option-correct': submitted && (correctAnswerArr(q.id)).includes(opt),
              'option-wrong': submitted && (answers[q.id] || []).includes(opt) && !(correctAnswerArr(q.id)).includes(opt)
            }"
          >
            <input
              type="checkbox"
              :value="opt"
              :checked="(answers[q.id] || []).includes(opt)"
              :disabled="submitted"
              @change="toggleMultiAnswer(q.id, opt)"
            />
            <span class="option-text">{{ opt }}</span>
          </label>
        </div>

        <!-- 判断题 -->
        <div v-else-if="q.type === 'true_false'" class="options-group">
          <label
            v-for="opt in ['正确', '错误']"
            :key="opt"
            class="option-item"
            :class="{
              'option-selected': answers[q.id] === opt,
              'option-correct': submitted && opt === correctAnswer(q.id),
              'option-wrong': submitted && answers[q.id] === opt && opt !== correctAnswer(q.id)
            }"
          >
            <input
              type="radio"
              :name="'q_' + q.id"
              :value="opt"
              :checked="answers[q.id] === opt"
              :disabled="submitted"
              @change="setAnswer(q.id, opt)"
            />
            <span class="option-text">{{ opt }}</span>
          </label>
        </div>

        <!-- 填空题 -->
        <div v-else-if="q.type === 'fill_blank'" class="fill-blank-group">
          <input
            type="text"
            class="fill-input"
            :value="answers[q.id] || ''"
            :disabled="submitted"
            @input="setAnswer(q.id, $event.target.value)"
            placeholder="请输入答案"
          />
          <div v-if="submitted && correctAnswer(q.id)" class="correct-answer">
            正确答案：<strong>{{ correctAnswer(q.id) }}</strong>
          </div>
        </div>

        <!-- 简答题 -->
        <div v-else-if="q.type === 'short_answer'" class="short-answer-group">
          <textarea
            class="short-textarea"
            :value="answers[q.id] || ''"
            :disabled="submitted"
            @input="setAnswer(q.id, $event.target.value)"
            placeholder="请输入你的回答"
            rows="3"
          ></textarea>
          <div v-if="submitted && correctAnswer(q.id)" class="correct-answer">
            参考答案：<strong>{{ correctAnswer(q.id) }}</strong>
          </div>
        </div>

        <!-- 解析 -->
        <div v-if="submitted && explanation(q.id)" class="explanation">
          <span class="explanation-label">解析：</span>
          {{ explanation(q.id) }}
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'QuizPanel',
  props: {
    quizData: {
      type: Object,
      required: true
    },
    submitResult: {
      type: Object,
      default: null
    },
    draftAnswers: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      answers: {},
      submitted: false
    }
  },
  created() {
    if (this.draftAnswers) {
      this.answers = { ...this.draftAnswers }
    }
  },
  computed: {
    scorePct() {
      if (!this.submitResult || this.submitResult.total === 0) return 0
      return Math.round((this.submitResult.score / this.submitResult.total) * 100)
    },
    scoreClass() {
      if (this.scorePct >= 80) return 'score-excellent'
      if (this.scorePct >= 60) return 'score-good'
      return 'score-poor'
    },
    scoreIcon() {
      if (this.scorePct >= 80) return 'Trophy'
      if (this.scorePct >= 60) return 'Medal'
      return 'Flag'
    },
    detailMap() {
      if (!this.submitResult || !this.submitResult.details) return {}
      const map = {}
      for (const d of this.submitResult.details) {
        map[String(d.questionId)] = d
      }
      return map
    }
  },
  methods: {
    typeLabel(type) {
      const map = {
        single_choice: '单选',
        multi_choice: '多选',
        fill_blank: '填空',
        true_false: '判断',
        short_answer: '简答'
      }
      return map[type] || type
    },
    setAnswer(qId, value) {
      this.answers = { ...this.answers, [qId]: value }
    },
    toggleMultiAnswer(qId, opt) {
      const current = this.answers[qId] || []
      const next = current.includes(opt)
        ? current.filter(o => o !== opt)
        : [...current, opt]
      this.answers = { ...this.answers, [qId]: next }
    },
    isCorrect(qId) {
      const d = this.detailMap[String(qId)]
      return d ? d.correct : false
    },
    correctAnswer(qId) {
      const d = this.detailMap[String(qId)]
      return d ? d.correctAnswer : ''
    },
    correctAnswerArr(qId) {
      const ans = this.correctAnswer(qId)
      if (!ans) return []
      if (Array.isArray(ans)) return ans
      try {
        const parsed = JSON.parse(ans)
        return Array.isArray(parsed) ? parsed : [ans]
      } catch {
        return [ans]
      }
    },
    explanation(qId) {
      const d = this.detailMap[String(qId)]
      return d ? d.explanation : ''
    },
    handleSubmit() {
      this.submitted = true
      this.$emit('submit', { answers: this.answers })
    },
    handleSaveDraft() {
      this.$emit('saveDraft', { answers: this.answers })
    }
  }
}
</script>

<style scoped>
.quiz-panel {
  padding: 24px;
  max-width: 800px;
  margin: 0 auto;
  height: 100%;
  overflow-y: auto;
}

.quiz-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 2px solid #e8e8e8;
}

.quiz-title {
  font-size: 20px;
  color: #1a1a1a;
  margin: 0;
}

.quiz-actions {
  display: flex;
  gap: 10px;
}

.btn {
  padding: 8px 20px;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s;
}

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-primary {
  background: #b8763d;
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: #a0682f;
}

.btn-secondary {
  background: #f5f5f5;
  color: #333;
  border: 1px solid #ddd;
}

.btn-secondary:hover {
  background: #eee;
}

.score-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 20px;
  border-radius: 8px;
  margin-bottom: 20px;
  font-size: 16px;
  font-weight: 600;
}

.score-excellent {
  background: #e8f5e9;
  color: #2e7d32;
}

.score-good {
  background: #fff8e1;
  color: #f57f17;
}

.score-poor {
  background: #fce4ec;
  color: #c62828;
}

.score-icon {
  font-size: 20px;
}

.questions-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.question-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 20px;
  transition: border-color 0.3s;
}

.question-correct {
  border-color: #4caf50;
  background: #f9fdf9;
}

.question-wrong {
  border-color: #ef5350;
  background: #fff9f9;
}

.question-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.question-number {
  font-weight: 600;
  color: #b8763d;
  font-size: 14px;
}

.question-type-badge {
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.type-single_choice { background: #f3e6d4; color: #a0682f; }
.type-multi_choice { background: #f3e5f5; color: #7b1fa2; }
.type-fill_blank { background: #e8f5e9; color: #2e7d32; }
.type-true_false { background: #fff3e0; color: #e65100; }
.type-short_answer { background: #fce4ec; color: #c62828; }

.question-stem {
  font-size: 15px;
  line-height: 1.7;
  color: #333;
  margin-bottom: 14px;
}

.options-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.option-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.option-item:hover {
  background: #faf5ed;
  border-color: #ecd9b8;
}

.option-selected {
  background: #f3e6d4;
  border-color: #b8763d;
}

.option-correct {
  background: #e8f5e9 !important;
  border-color: #4caf50 !important;
}

.option-wrong {
  background: #ffebee !important;
  border-color: #ef5350 !important;
}

.option-text {
  font-size: 14px;
  color: #333;
}

.fill-input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  transition: border-color 0.2s;
}

.fill-input:focus {
  outline: none;
  border-color: #b8763d;
  box-shadow: 0 0 0 3px rgba(184, 118, 61, 0.1);
}

.short-textarea {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  resize: vertical;
  min-height: 80px;
  font-family: inherit;
  transition: border-color 0.2s;
}

.short-textarea:focus {
  outline: none;
  border-color: #b8763d;
  box-shadow: 0 0 0 3px rgba(184, 118, 61, 0.1);
}

.correct-answer {
  margin-top: 10px;
  padding: 8px 14px;
  background: #e8f5e9;
  border-radius: 6px;
  font-size: 14px;
  color: #2e7d32;
}

.explanation {
  margin-top: 14px;
  padding: 12px 16px;
  background: #f5f5f5;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: #555;
}

.explanation-label {
  font-weight: 600;
  color: #333;
}
</style>
