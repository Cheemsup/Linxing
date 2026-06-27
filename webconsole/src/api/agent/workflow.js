import api from '@/api'

/**
 * Agent 工作流相关 API
 */
export const workflowApi = {
  /**
   * 提交 HumanInTheLoop 澄清回复
   * @param {number|string} sessionId 会话 ID（作为澄清请求标识）
   * @param {string} answer 用户回复内容
   * @returns {Promise} 操作结果，包含 completed 字段
   */
  submitClarification(sessionId, answer) {
    return api.post('/agent/workflow/clarify', {
      sessionId,
      answer
    })
  }
}
