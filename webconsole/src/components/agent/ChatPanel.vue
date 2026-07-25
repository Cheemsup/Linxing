<template>
  <div class="chat-panel-wrapper">
    <div class="chat-panel">
      <div class="chat-header">
        <span class="chat-header-title">{{ activeSessionTitle || '新对话' }}</span>
        <button
          class="tree-btn"
          @click="showTreeModal = true"
          :disabled="!activeSessionId || !chatTreeStore.state.messages.length"
          title="查看对话树"
        >
          <el-icon><Share /></el-icon>
          <span>对话树</span>
        </button>
      </div>
      <div class="chat-messages" ref="messagesContainer">
        <template v-for="item in allMessages" :key="item.id">
          <div class="message-row" :data-msg-id="item.id">
            <div :class="['message', item.role]">
              <div class="message-content">
                <div v-if="item.role === 'user'" class="user-message">
                  {{ item.content }}
                </div>
                <div v-else class="bot-message">
                  <template v-if="getStepsForMessage(item).length">
                    <div class="collapsible-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'step') }">
                      <div class="panel-header" @click="togglePanel(item.id, 'step')">
                        <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'step') ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                        <span class="panel-title">思考过程</span>
                        <span class="panel-badge">{{ getStepsForMessage(item).length }}步</span>
                      </div>
                      <div v-show="!isPanelCollapsed(item.id, 'step')" class="panel-body">
                        <template v-for="(step, idx) in getStepsForMessage(item)" :key="idx">
                          <div v-if="step.eventType === 'thinking'" class="collapsible-panel sub-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'thinking_' + idx) }">
                            <div class="panel-header" @click="togglePanel(item.id, 'thinking_' + idx)">
                              <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'thinking_' + idx) ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                              <el-icon class="step-icon"><MagicStick /></el-icon>
                              <span class="panel-title">{{ step.thinkingContent ? '思考详情' : '正在思考...' }}</span>
                            </div>
                            <div v-show="!isPanelCollapsed(item.id, 'thinking_' + idx)" class="panel-body">
                              <div v-if="step.thinkingContent" class="thinking-content">{{ step.thinkingContent }}</div>
                              <div v-else class="step-placeholder">等待思考内容...</div>
                            </div>
                          </div>
                          <!-- 修复：本 v-if 分支同时被实时 stepEvents（扁平）与刷新后的 historyStepsCache（树）命中，
                               后者 sub_agent 节点带 children，须渲染嵌套子步骤，否则历史回看被扁平化。
                               与下方 v-else 历史回看模板保持一致：clarify 系列 → 状态文本；sub_agent → 可折叠面板含 children。 -->
                          <div v-else-if="isClarifyStep(step)" class="step-item step-sub-agent clarify-step">
                            <el-icon class="step-icon"><component :is="getStepIcon(step)" /></el-icon>
                            <div class="clarify-content">
                              <div class="step-text">{{ formatStepText(step) }}</div>
                              <div class="clarify-status clarify-done">
                                {{ step.stepData && step.stepData.agent_role === 'clarify_answer'
                                   ? (step.answer || step.content || '已补充信息')
                                   : '已基于现有信息生成初版内容' }}
                              </div>
                            </div>
                          </div>
                          <div v-else-if="step.eventType === 'sub_agent'" class="collapsible-panel sub-panel sub-agent-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'subagent_' + step.id) }">
                            <div class="panel-header" @click="togglePanel(item.id, 'subagent_' + step.id)">
                              <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'subagent_' + step.id) ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                              <el-icon class="step-icon"><Cpu /></el-icon>
                              <span class="panel-title">{{ formatStepText(step) }}</span>
                            </div>
                            <div v-show="!isPanelCollapsed(item.id, 'subagent_' + step.id)" class="panel-body">
                              <template v-for="(child, cidx) in (step.children || [])" :key="'c_' + cidx">
                                <div v-if="child.eventType === 'thinking'" class="collapsible-panel sub-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'thinking_' + step.id + '_' + cidx) }">
                                  <div class="panel-header" @click="togglePanel(item.id, 'thinking_' + step.id + '_' + cidx)">
                                    <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'thinking_' + step.id + '_' + cidx) ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                                    <el-icon class="step-icon"><MagicStick /></el-icon>
                                    <span class="panel-title">{{ child.thinkingContent ? '思考详情' : '正在思考...' }}</span>
                                  </div>
                                  <div v-show="!isPanelCollapsed(item.id, 'thinking_' + step.id + '_' + cidx)" class="panel-body">
                                    <div v-if="child.thinkingContent" class="thinking-content">{{ child.thinkingContent }}</div>
                                    <div v-else class="step-placeholder">无思考内容</div>
                                  </div>
                                </div>
                                <div v-else :class="['step-item', getStepClass(child)]">
                                  <el-icon class="step-icon"><component :is="getStepIcon(child)" /></el-icon>
                                  <span class="step-text">{{ formatStepText(child) }}</span>
                                </div>
                              </template>
                              <div v-if="!(step.children && step.children.length)" class="step-placeholder">无子步骤</div>
                            </div>
                          </div>
                          <div v-else :class="['step-item', getStepClass(step)]">
                            <el-icon class="step-icon"><component :is="getStepIcon(step)" /></el-icon>
                            <span class="step-text">{{ formatStepText(step) }}</span>
                          </div>
                        </template>
                      </div>
                    </div>
                    <div class="collapsible-panel answer-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'answer') }">
                      <div class="panel-header" @click="togglePanel(item.id, 'answer')">
                        <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'answer') ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                        <span class="panel-title">回答</span>
                        <span class="panel-badge">已生成</span>
                      </div>
                      <div v-show="!isPanelCollapsed(item.id, 'answer')" class="panel-body">
                        <div class="answer" v-html="formatAnswer(item.content)"></div>
                        <div v-if="item.sourceDetails && item.sourceDetails.length" class="sources">
                          <span class="source-label">来源:</span>
                          <span
                            v-for="(source, si) in item.sourceDetails"
                            :key="si"
                            class="source-tag clickable"
                            @click="openChunkContext(source)"
                            :title="'点击查看上下文: ' + (source.titlePath || source.fileName)"
                          >
                            {{ source.fileName }}{{ source.titlePath ? ' > ' + source.titlePath : '' }}
                          </span>
                        </div>
                      </div>
                    </div>
                  </template>
                  <template v-else>
                    <div class="collapsible-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'step') }">
                      <div class="panel-header" @click="toggleHistoryStepsPanel(item)">
                        <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'step') ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                        <span class="panel-title">思考过程</span>
                        <span class="panel-badge" v-if="historyStepsCache[item.id]">{{ historyStepsCache[item.id].length }}步</span>
                        <span class="panel-badge" v-else-if="loadingSteps[item.id]">加载中...</span>
                      </div>
                      <div v-show="!isPanelCollapsed(item.id, 'step')" class="panel-body">
                        <template v-if="loadingSteps[item.id]">
                          <div class="step-placeholder">
                            <StarLoader :size="16" :show-elapsed="false" />
                            <span>加载中...</span>
                          </div>
                        </template>
                        <template v-else-if="historyStepsCache[item.id] && historyStepsCache[item.id].length">
                          <template v-for="(step, idx) in historyStepsCache[item.id]" :key="idx">
                            <div v-if="step.eventType === 'thinking'" class="collapsible-panel sub-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'thinking_' + idx) }">
                              <div class="panel-header" @click="togglePanel(item.id, 'thinking_' + idx)">
                              <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'thinking_' + idx) ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                              <el-icon class="step-icon"><MagicStick /></el-icon>
                              <span class="panel-title">{{ step.thinkingContent ? '思考详情' : '正在思考...' }}</span>
                            </div>
                            <div v-show="!isPanelCollapsed(item.id, 'thinking_' + idx)" class="panel-body">
                              <div v-if="step.thinkingContent" class="thinking-content">{{ step.thinkingContent }}</div>
                              <div v-else class="step-placeholder">无思考内容</div>
                            </div>
                          </div>
                          <!-- 修复：历史回看中 clarify 系列事件渲染为状态文本，而非空 sub_agent 面板 -->
                          <div v-else-if="isClarifyStep(step)" class="step-item step-sub-agent clarify-step">
                            <el-icon class="step-icon"><component :is="getStepIcon(step)" /></el-icon>
                            <div class="clarify-content">
                              <div class="step-text">{{ formatStepText(step) }}</div>
                              <div class="clarify-status clarify-done">
                                {{ step.stepData && step.stepData.agent_role === 'clarify_answer'
                                   ? (step.answer || step.content || '已补充信息')
                                   : '已基于现有信息生成初版内容' }}
                              </div>
                            </div>
                          </div>
                          <!-- 0724 改进四：sub_agent 渲染为可折叠面板，children 为子 Agent 内部 step（按 agent_id 分组配对） -->
                          <div v-else-if="step.eventType === 'sub_agent'" class="collapsible-panel sub-panel sub-agent-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'subagent_' + step.id) }">
                            <div class="panel-header" @click="togglePanel(item.id, 'subagent_' + step.id)">
                              <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'subagent_' + step.id) ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                              <el-icon class="step-icon"><Cpu /></el-icon>
                              <span class="panel-title">{{ formatStepText(step) }}</span>
                            </div>
                            <div v-show="!isPanelCollapsed(item.id, 'subagent_' + step.id)" class="panel-body">
                              <template v-for="(child, cidx) in (step.children || [])" :key="'c_' + cidx">
                                <div v-if="child.eventType === 'thinking'" class="collapsible-panel sub-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'thinking_' + step.id + '_' + cidx) }">
                                  <div class="panel-header" @click="togglePanel(item.id, 'thinking_' + step.id + '_' + cidx)">
                                    <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'thinking_' + step.id + '_' + cidx) ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                                    <el-icon class="step-icon"><MagicStick /></el-icon>
                                    <span class="panel-title">{{ child.thinkingContent ? '思考详情' : '正在思考...' }}</span>
                                  </div>
                                  <div v-show="!isPanelCollapsed(item.id, 'thinking_' + step.id + '_' + cidx)" class="panel-body">
                                    <div v-if="child.thinkingContent" class="thinking-content">{{ child.thinkingContent }}</div>
                                    <div v-else class="step-placeholder">无思考内容</div>
                                  </div>
                                </div>
                                <div v-else :class="['step-item', getStepClass(child)]">
                                  <el-icon class="step-icon"><component :is="getStepIcon(child)" /></el-icon>
                                  <span class="step-text">{{ formatStepText(child) }}</span>
                                </div>
                              </template>
                              <div v-if="!(step.children && step.children.length)" class="step-placeholder">无子步骤</div>
                            </div>
                          </div>
                          <div v-else :class="['step-item', getStepClass(step)]">
                            <el-icon class="step-icon"><component :is="getStepIcon(step)" /></el-icon>
                            <span class="step-text">{{ formatStepText(step) }}</span>
                          </div>
                          </template>
                        </template>
                        <template v-else>
                          <div class="step-placeholder">暂无思考步骤</div>
                        </template>
                      </div>
                    </div>
                    <div class="collapsible-panel answer-panel" :class="{ collapsed: isPanelCollapsed(item.id, 'answer') }">
                      <div class="panel-header" @click="togglePanel(item.id, 'answer')">
                        <el-icon class="panel-toggle"><component :is="isPanelCollapsed(item.id, 'answer') ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                        <span class="panel-title">回答</span>
                        <span class="panel-badge">已生成</span>
                      </div>
                      <div v-show="!isPanelCollapsed(item.id, 'answer')" class="panel-body">
                        <div class="answer" v-html="formatAnswer(item.content)"></div>
                        <div v-if="item.sourceDetails && item.sourceDetails.length" class="sources">
                          <span class="source-label">来源:</span>
                          <span
                            v-for="(source, si) in item.sourceDetails"
                            :key="si"
                            class="source-tag clickable"
                            @click="openChunkContext(source)"
                            :title="'点击查看上下文: ' + (source.titlePath || source.fileName)"
                          >
                            {{ source.fileName }}{{ source.titlePath ? ' > ' + source.titlePath : '' }}
                          </span>
                        </div>
                        <div v-else-if="item.sources && item.sources.length" class="sources">
                          <span class="source-label">来源:</span>
                          <span v-for="source in item.sources" :key="source" class="source-tag">{{ source }}</span>
                        </div>
                      </div>
                    </div>
                  </template>
                </div>
              </div>
              <div v-if="item.role === 'user'" class="message-actions">
                <button class="action-btn" @click="reAsk(item.id, item.content)" title="从此处分支并重新提问">重新提问</button>
                <button class="action-btn" @click="branchFrom(item.id)" title="从此处重新提问">分支</button>
                <button class="action-btn action-delete" @click="handleDeleteSubtree(item.id)" title="删除此分支">删除</button>
                <button v-if="item.id === chatTreeStore.state.branchParentId" class="action-btn action-cancel-branch" @click="cancelBranch" title="取消从此处分支">取消分支</button>
              </div>
            </div>
          </div>
        </template>

        <div v-if="tempUserMsg" class="message-row">
          <div class="message user">
            <div class="message-content">
              <div class="user-message">
                {{ tempUserMsg.content }}
              </div>
            </div>
          </div>
        </div>

        <div v-if="loading" class="message-row">
          <div class="message assistant">
            <div class="message-content">
              <div class="bot-message agent-output">

                <div class="collapsible-panel" :class="{ collapsed: stepCollapsed }">
                  <div class="panel-header" @click="stepCollapsed = !stepCollapsed">
                    <el-icon class="panel-toggle"><component :is="stepCollapsed ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                    <span class="panel-title">思考过程</span>
                    <span class="panel-badge" v-if="stepEvents.length">{{ stepEvents.length }}步</span>
                  </div>
                  <div v-show="!stepCollapsed" class="panel-body" ref="streamingStepBody" @scroll="onStreamingStepScroll">
                    <div v-if="!stepEvents.length && !isStreaming" class="step-placeholder">
                      <StarLoader :size="16" :show-elapsed="false" />
                      <span>等待思考开始...</span>
                    </div>
                    <template v-for="(step, idx) in stepTree" :key="idx">
                      <div v-if="step.eventType === 'thinking'" class="collapsible-panel sub-panel" :class="{ collapsed: step.thinkingCollapsed }">
                        <div class="panel-header" @click="step.thinkingCollapsed = !step.thinkingCollapsed">
                          <el-icon class="panel-toggle"><component :is="step.thinkingCollapsed ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                          <el-icon class="step-icon"><MagicStick /></el-icon>
                          <span class="panel-title">{{ step.thinkingContent ? '思考详情' : '正在思考...' }}</span>
                        </div>
                        <div v-show="!step.thinkingCollapsed" class="panel-body">
                          <div v-if="step.thinkingContent" class="thinking-content">{{ step.thinkingContent }}</div>
                          <div v-else class="step-placeholder">等待思考内容...</div>
                        </div>
                      </div>
                      <!-- 0724 改造C：sub_agent 流式实时可折叠面板，children 为子 Agent 内部 step -->
                      <!-- 修复：clarify 系列事件同为 sub_agent 类型，须先于 sub_agent 面板分支判定，否则输入框分支不可达 -->
                      <div v-else-if="isClarifyStep(step)" class="step-item step-sub-agent clarify-step">
                        <el-icon class="step-icon"><component :is="getStepIcon(step)" /></el-icon>
                        <div class="clarify-content">
                          <div class="step-text">{{ formatStepText(step) }}</div>
                          <!-- clarify 提问（有 question）：展示输入框；clarify_answer（无 question）：展示状态反馈 -->
                          <template v-if="step.stepData && step.stepData.question">
                            <div v-if="!getClarifyState(idx).submitted && !getClarifyState(idx).expired" class="clarify-input-area">
                              <textarea
                                :value="getClarifyState(idx).answer"
                                @input="setClarifyAnswer(idx, $event.target.value)"
                                placeholder="请输入你的补充信息..."
                                rows="2"
                                class="clarify-input"
                                @keydown.enter.exact.prevent="submitClarify(idx)"
                              ></textarea>
                              <button
                                class="clarify-submit-btn"
                                @click="submitClarify(idx)"
                                :disabled="getClarifyState(idx).submitting || !getClarifyState(idx).answer || !getClarifyState(idx).answer.trim()"
                              >
                                {{ getClarifyState(idx).submitting ? '提交中...' : '补充信息' }}
                              </button>
                            </div>
                            <div v-else-if="getClarifyState(idx).submitted" class="clarify-status clarify-done">
                              已回复：{{ getClarifyState(idx).answer }}
                            </div>
                            <div v-else class="clarify-status clarify-expired">
                              已基于现有信息生成初版内容
                            </div>
                          </template>
                          <!-- clarify_answer 回复/超时/中断事件：直接展示后端给出的状态文本 -->
                          <div v-else class="clarify-status clarify-done">
                            {{ step.answer || step.content || (step.error ? '澄清已中断' : '已收到补充信息') }}
                          </div>
                        </div>
                      </div>
                      <div v-else-if="step.eventType === 'sub_agent'" class="collapsible-panel sub-panel sub-agent-panel" :class="{ collapsed: step.thinkingCollapsed }">
                        <div class="panel-header" @click="step.thinkingCollapsed = !step.thinkingCollapsed">
                          <el-icon class="panel-toggle"><component :is="step.thinkingCollapsed ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                          <el-icon class="step-icon"><Cpu /></el-icon>
                          <span class="panel-title">{{ formatStepText(step) }}</span>
                          <!-- sub_agent 执行中纯转圈（无独立心跳源，不计时）；isStepRunning 据 completed 标记停动画 -->
                          <StarLoader v-if="isStepRunning(step, stepEvents, isStreaming)" :size="14" :show-elapsed="false" />
                        </div>
                        <div v-show="!step.thinkingCollapsed" class="panel-body">
                          <template v-for="(child, cidx) in (step.children || [])" :key="'sc_' + cidx">
                            <div v-if="child.eventType === 'thinking'" class="collapsible-panel sub-panel" :class="{ collapsed: child.thinkingCollapsed }">
                              <div class="panel-header" @click="child.thinkingCollapsed = !child.thinkingCollapsed">
                                <el-icon class="panel-toggle"><component :is="child.thinkingCollapsed ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                                <el-icon class="step-icon"><MagicStick /></el-icon>
                                <span class="panel-title">{{ child.thinkingContent ? '思考详情' : '正在思考...' }}</span>
                              </div>
                              <div v-show="!child.thinkingCollapsed" class="panel-body">
                                <div v-if="child.thinkingContent" class="thinking-content">{{ child.thinkingContent }}</div>
                                <div v-else class="step-placeholder">无思考内容</div>
                              </div>
                            </div>
                            <div v-else :class="['step-item', getStepClass(child)]">
                              <el-icon class="step-icon" :class="{ 'is-loading': isStepRunning(child, stepEvents, isStreaming) }"><component :is="getStepIcon(child)" /></el-icon>
                              <span class="step-text">{{ formatStepText(child) }}</span>
                              <StarLoader
                                v-if="isStepRunning(child, stepEvents, isStreaming)"
                                :size="14"
                                :elapsed-seconds="child.elapsedSeconds || null"
                                :show-elapsed="child.elapsedSeconds != null"
                              />
                            </div>
                          </template>
                          <div v-if="!(step.children && step.children.length)" class="step-placeholder">无子步骤</div>
                        </div>
                      </div>
                      <div v-else :class="['step-item', getStepClass(step)]">
                        <el-icon class="step-icon" :class="{ 'is-loading': isStepRunning(step, stepEvents, isStreaming) }"><component :is="getStepIcon(step)" /></el-icon>
                        <span class="step-text">{{ formatStepText(step) }}</span>
                        <!-- 0724 改进五：工具执行中用四芒星 StarLoader 替代"执行中..."文案，显示"已 N 秒"计时 -->
                        <StarLoader
                          v-if="isStepRunning(step, stepEvents, isStreaming)"
                          :size="14"
                          :elapsed-seconds="step.elapsedSeconds || null"
                        />
                      </div>
                    </template>
                    <div v-if="isStreaming && !stepEvents.length" class="step-item step-thinking">
                      <StarLoader :size="16" :show-elapsed="false" />
                      <span>正在思考...</span>
                    </div>
                  </div>
                </div>

                <div class="collapsible-panel answer-panel" :class="{ collapsed: answerCollapsed }">
                  <div class="panel-header" @click="answerCollapsed = !answerCollapsed">
                    <el-icon class="panel-toggle"><component :is="answerCollapsed ? 'ArrowRight' : 'ArrowDown'" /></el-icon>
                    <span class="panel-title">回答</span>
                    <span class="panel-badge" v-if="streamingText || isStreaming">
                      {{ isStreaming ? '生成中' : '已生成' }}
                    </span>
                  </div>
                  <div v-show="!answerCollapsed" class="panel-body" ref="streamingAnswerBody" @scroll="onStreamingAnswerScroll">
                    <div v-if="isStreaming && streamingHtml" class="answer streaming-answer streaming-md" v-html="streamingHtml"></div>
                    <div v-else-if="isStreaming" class="answer streaming-answer streaming-plain">{{ streamingText }}</div>
                    <div v-else-if="streamingText" class="answer" v-html="formatAnswer(streamingText)"></div>
                    <div v-else class="step-placeholder">
                      <StarLoader :size="16" :show-elapsed="false" />
                      <span>等待回答...</span>
                    </div>
                    <span v-if="isStreaming" class="streaming-cursor">|</span>
                  </div>
                </div>

              </div>
            </div>
          </div>
        </div>

      </div>

      <div class="chat-input">
        <textarea
          v-model="question"
          @keydown.enter.exact.prevent="sendQuestion"
          rows="3"
        ></textarea>
        <button
          @click="handleSendClick"
          :disabled="!loading && !question.trim()"
          :class="[
            chatTreeStore.state.branchParentId ? 'btn-send-branch' : '',
            loading ? 'btn-stop' : ''
          ]"
          :title="loading ? '处理中（中断功能开发中）' : (chatTreeStore.state.branchParentId ? '发送新分支消息' : '发送')"
        >
          <span v-if="loading" class="stop-icon" aria-hidden="true"></span>
          <template v-else>
            {{ chatTreeStore.state.branchParentId ? '发送新分支消息' : '发送' }}
          </template>
        </button>
      </div>
    </div>

    <div v-if="showContextPanel" class="context-overlay" @click.self="showContextPanel = false">
      <div class="context-modal">
        <ChunkContextPanel
          ref="contextPanel"
          @close="showContextPanel = false"
          @navigate="handleNavigate"
        />
      </div>
    </div>

    <div v-if="showTreeModal" class="tree-overlay" @click.self="showTreeModal = false">
      <div class="tree-modal">
        <ChatTreePanel
          :roots="userQuestionRoots"
          :active-path="userQuestionActivePath"
          @close="showTreeModal = false"
          @select="handleTreeSelect"
        />
      </div>
    </div>
  </div>
</template>

<script>
import { ragApi, chatSessionApi } from '@/api/agent/chat'
import { workflowApi } from '@/api/agent/workflow'
import ChunkContextPanel from './ChunkContextPanel.vue'
import ChatTreePanel from './ChatTreePanel.vue'
import StarLoader from './StarLoader.vue'
import { chatTreeStore } from '@/stores/agent/chatTreeStore'
import { chatSessionStore } from '@/stores/agent/chatSessionStore'
import { useMarkdownRenderer } from '@/composables/useMarkdownRenderer'

const { renderToHtml } = useMarkdownRenderer()
// 自定义链接占位：在 markdown 渲染前把内部跳转链接（测验/学习计划）替换为占位符，
// 渲染完成后再还原为可点击 span，避免被 markdown-it 转义或破坏。
const EXAM_LINK_PLACEHOLDER = (id, label) => `PH_EXAM_${id}_${label}_EXAM_PH`
const PLAN_LINK_PLACEHOLDER = (id, label) => `PH_PLAN_${id}_${label}_PLAN_PH`
const EXAM_LINK_RE = /PH_EXAM_(\d+)_(.+?)_EXAM_PH/g
const PLAN_LINK_RE = /PH_PLAN_(\d+)_(.+?)_PLAN_PH/g

export default {
  name: 'ChatPanel',
  components: {
    ChunkContextPanel,
    ChatTreePanel,
    StarLoader
  },
  data() {
    return {
      question: '',
      loading: false,
      tempUserMsg: null,
      showContextPanel: false,
      showTreeModal: false,
      streamingText: '',
      isStreaming: false,
      // 流式期间实时渲染的 markdown HTML（与 streamingText 同步更新，按节流频率重解析）
      streamingHtml: '',
      // 渲染节流定时器：流式 token 高频到来时，markdown 重解析按固定节拍进行，避免逐 token 重建 DOM
      renderTimer: null,
      stepEvents: [],
      // 0724 改造C：流式实时树——onStep 收到带 stepId/parentStepId 的事件时归集到树，模板遍历 stepTree 而非 stepEvents。
      // stepNodeMap 为 id→node 快速查找（挂载子 step 到 parent 用）。sub_agent start/end 配对同 buildStepTree。
      stepTree: [],
      stepNodeMap: {},
      subAgentStartsByAgent: {}, // agentId → start node（流式配对 end 用）
      stepCollapsed: false,
      answerCollapsed: false,
      tokenBuffer: '',
      thinkingBuffer: '',
      flushTimer: null,
      tokenGroups: {},
      currentStreamStepNumber: 0,
      messagePanelState: {},
      historyStepsCache: {},
      loadingSteps: {},
      // 抑制 watch.activeSessionId 的自动 loadMessages。
      // sendQuestion 内部 createSession 会改变 activeSessionId，但此时会话刚创建、
      // 马上要发起流式请求，不应触发 load（否则与 onResult 的消息追加重复，产生空节点）。
      suppressWatchLoad: false,
      // HumanInTheLoop 澄清输入框状态：{ [stepIdx]: { answer: '', submitting: false, submitted: false } }
      clarifyInputs: {},
      // 流式贴底跟随状态：用户在流式期间上划查看历史内容时为 false，停止跟随；
      // 回到底部附近时自动恢复为 true，实时跟随最新输出（kimi/智谱清言/deepseek 风格）。
      // 两个窗口各自独立追踪：stepBody 对应"思考过程"，answerBody 对应"回答"。
      stepPinned: true,
      answerPinned: true
    }
  },
  computed: {
    chatTreeStore() {
      return chatTreeStore
    },
    activeSessionId() {
      return chatSessionStore.state.activeSessionId
    },
    activeSessionTitle() {
      if (!this.activeSessionId) return ''
      const s = chatSessionStore.state.sessions.find(s => s.id === this.activeSessionId)
      return s ? s.title : ''
    },
    messageMap() {
      return chatTreeStore.getMessageMap()
    },
    messageTree() {
      const map = this.messageMap
      const roots = []
      chatTreeStore.state.messages.forEach(m => {
        if (!m.parentId || !map.has(m.parentId)) {
          roots.push(map.get(m.id))
        }
      })
      return roots
    },
    allMessages() {
      const result = []
      const activeLeafId = chatTreeStore.state.activeLeafId
      if (!activeLeafId || !this.messageMap.has(activeLeafId)) {
        return result
      }
      const roots = this.messageTree

      const containsLeaf = (node) => {
        if (node.id === activeLeafId) return true
        if (node.children && node.children.length) {
          return node.children.some(child => containsLeaf(child))
        }
        return false
      }

      const walk = (node) => {
        const enriched = { ...node, sourceDetails: this.parseSourceDetails(node.sources) }
        result.push(enriched)
        if (node.id === activeLeafId) {
          return
        }
        if (node.children && node.children.length) {
          const activeChild = node.children.find(c => containsLeaf(c))
          if (activeChild) {
            for (const child of node.children) {
              if (child !== activeChild && child.role === 'assistant') {
                const enrichedSibling = { ...child, sourceDetails: this.parseSourceDetails(child.sources) }
                result.push(enrichedSibling)
              }
            }
            walk(activeChild)
          }
        }
      }

      for (const root of roots) {
        if (containsLeaf(root)) {
          walk(root)
        }
      }
      return result
    },
    userQuestionRoots() {
      const buildUserTree = (node) => {
        const questions = []
        if (node.role === 'user') {
          const userNode = { id: node.id, content: node.content, children: [] }
          node.children.forEach(child => {
            if (child.role === 'assistant') {
              child.children.forEach(grandChild => {
                const subs = buildUserTree(grandChild)
                subs.forEach(sub => userNode.children.push(sub))
              })
            } else if (child.role === 'user') {
              const subs = buildUserTree(child)
              subs.forEach(sub => userNode.children.push(sub))
            }
          })
          questions.push(userNode)
        } else {
          node.children.forEach(child => {
            const subs = buildUserTree(child)
            subs.forEach(sub => questions.push(sub))
          })
        }
        return questions
      }
      const roots = []
      this.messageTree.forEach(root => {
        const qs = buildUserTree(root)
        qs.forEach(q => roots.push(q))
      })
      return roots
    },
    userQuestionActivePath() {
      return chatTreeStore.getUserActivePath()
    },
    activePath() {
      return chatTreeStore.getActivePathIds()
    }
  },
  watch: {
    // 监听共享 store 的活跃会话变化：切换会话时加载消息，新对话时清空。
    // suppressWatchLoad 为 true 时跳过（用于 sendQuestion 内部 createSession，
    // 避免与流式响应的消息追加产生重复节点）。
    activeSessionId(val, oldVal) {
      if (this.suppressWatchLoad) return
      if (val && val !== oldVal) {
        this.switchSession(val)
      } else if (!val) {
        chatTreeStore.clearMessages()
        this.question = ''
        this.messagePanelState = {}
        this.historyStepsCache = {}
        this.loadingSteps = {}
      }
    },
    // 侧栏点历史项跳 /chat/:id 时同路由只换 params，组件不重建，靠此 watch 切换会话
    '$route.params.sessionId'(val, oldVal) {
      if (!val || val === oldVal) return
      chatSessionStore.setActiveSession(val)
      this.switchSession(val)
    }
  },
  async mounted() {
    const sid = this.$route.params.sessionId
    if (sid) {
      // 抑制 watch.activeSessionId 的自动 load：下面手动 switchSession 加载，
      // 否则 watch 触发的 switchSession 会异步清空 this.question，吞掉 pendingQuestion。
      this.suppressWatchLoad = true
      chatSessionStore.setActiveSession(sid)
      await this.switchSession(sid)
      this.suppressWatchLoad = false
    }
    // 消费首页透传的待发问题，填入输入框后立即发送（chatStream 在本页发起）
    const pending = chatSessionStore.consumePendingQuestion()
    if (pending) {
      this.question = pending
      this.$nextTick(() => this.sendQuestion())
    }
    window.__examLinkClick = (examId) => {
      this.$router.push({ name: 'ExamDetail', params: { examId } })
    }
    window.__studyPlanLinkClick = (planId) => {
      this.$router.push({ name: 'StudyPlanDetail', params: { planId } })
    }
  },
  beforeUnmount() {
    delete window.__examLinkClick
    delete window.__studyPlanLinkClick
  },
  methods: {
    async fetchSessions() {
      await chatSessionStore.fetchSessions()
    },
    async switchSession(id) {
      chatSessionStore.setActiveSession(id)
      chatTreeStore.clearBranch()
      chatTreeStore.setActiveLeaf(null)
      this.question = ''
      this.messagePanelState = {}
      this.historyStepsCache = {}
      this.loadingSteps = {}
      await this.loadMessages()
    },
    async loadMessages(activeLeafId = null) {
      if (!this.activeSessionId) {
        chatTreeStore.clearMessages()
        return
      }
      try {
        const res = await chatSessionApi.getMessages(this.activeSessionId)
        let data = res.data.data || res.data
        if (!Array.isArray(data)) {
          data = []
        }
        // 后端 ChatMessageVO 的消息类型字段名为 type（user/assistant/summary），
        // 前端模板与树遍历逻辑统一读 role。历史消息需在此归一化 type→role，
        // 否则 user 消息因 role===undefined 落入 v-else 的 bot-message 分支，
        // 被错误渲染出"思考过程+回答"双面板。
        data = data.map(m => ({ ...m, role: m.role || m.type }))
        chatTreeStore.setMessages(data)
        if (activeLeafId) {
          chatTreeStore.setActiveLeaf(activeLeafId)
        } else if (data.length > 0) {
          const lastAssistant = data.filter(m => m.role === 'assistant').pop()
          chatTreeStore.setActiveLeaf(lastAssistant ? lastAssistant.id : data[data.length - 1].id)
        }
      } catch (e) {
        console.error('加载消息失败:', e)
        chatTreeStore.clearMessages()
        // 会话 id 后端已不存在（如直访被删除的书签），兜底回首页
        chatSessionStore.startNewChat()
        this.$router.replace('/chat/home')
      }
      this.$nextTick(() => this.scrollToBottom())
    },

    resetStreamState() {
      this.streamingText = ''
      this.streamingHtml = ''
      this.isStreaming = false
      this.stepEvents = []
      this.resetStepTree()
      this.stepCollapsed = false
      this.answerCollapsed = false
      this.tokenBuffer = ''
      this.thinkingBuffer = ''
      this.tokenGroups = {}
      this.currentStreamStepNumber = 0
      // 每次新的流式请求开始时，清空 HumanInTheLoop 澄清输入框状态，
      // 避免同页面会话中上一次工作流的澄清回复被复用到新工作流。
      this.clarifyInputs = {}
      // 新一轮流式开始，两个窗口默认贴底跟随最新内容
      this.stepPinned = true
      this.answerPinned = true
      if (this.flushTimer) {
        clearTimeout(this.flushTimer)
        this.flushTimer = null
      }
      if (this.renderTimer) {
        clearTimeout(this.renderTimer)
        this.renderTimer = null
      }
    },

    handleSendClick() {
      // 处理中时点击为中断入口（中断功能开发中，暂不处理）
      if (this.loading) return
      this.sendQuestion()
    },
    async sendQuestion() {
      if (!this.question.trim() || this.loading) return

      const q = this.question.trim()
      this.question = ''
      this.loading = true
      this.resetStreamState()

      this.tempUserMsg = { content: q }
      this.$nextTick(() => this.scrollToBottom())

      // 聊天页进来时 activeSessionId 必由路由 params 给定，会话已存在，无需在此创建。
      // 首条消息发送后触发 AI 自动命名（KIMI 风格）。
      const needsAutoTitle = chatTreeStore.state.messages.length === 0

      let parentMessageId
      if (chatTreeStore.state.branchParentId) {
        const map = chatTreeStore.getMessageMap()
        const branchNode = map.get(chatTreeStore.state.branchParentId)
        parentMessageId = branchNode ? branchNode.parentId : null
      } else {
        parentMessageId = chatTreeStore.state.activeLeafId
      }

      const vm = this
      ragApi.chatStream({
        question: q,
        sessionId: this.activeSessionId,
        parentMessageId: parentMessageId,
        onStep(data) {
          const stepNumber = data.stepNumber || 0

          // 先 flush 剩余 token，确保 thinkingContent 有最新内容
          vm.flushTokenBuffer()

          // 清理该 stepNumber 的 tokenGroups（已通过 flushTokenBuffer 回填到 thinkingContent）
          delete vm.tokenGroups[stepNumber]

          if (data.eventType === 'thinking') {
            // thinking 事件：创建思考窗口，token 通过 flushTokenBuffer 实时回填
            const tnode = {
              eventType: 'thinking',
              stepNumber: stepNumber,
              phase: data.phase,
              thinkingContent: '',
              stepId: data.stepId || null,
              parentStepId: data.parentStepId || (data.stepData && data.stepData.parent_step_id) || null,
              agentId: data.agentId || (data.stepData && data.stepData.agent_id) || null,
              thinkingCollapsed: false
            }
            vm.stepEvents.push(tnode)
            vm.addToStepTree(tnode)
          } else if (data.eventType === 'tool_progress') {
            // 0724 改进二：工具执行心跳——不入 stepEvents（保活信号非业务步骤），
            // 只更新对应 tool_call step 的 elapsedSeconds，驱动 StarLoader 计时 + 动画
            // Vue 3 Proxy 响应式，直接赋值即可触发更新
            // 0724 计时准确性改造 D：按 tool_call_id 精确匹配当前正在执行的 tool_call step，
            // 而非"最后一个 tool_call"——连续多工具调用时后者会错位到上一个工具。
            const sd = data.stepData || {}
            const elapsed = sd.elapsed_seconds != null ? sd.elapsed_seconds : 0
            const tcid = sd.tool_call_id
            let target = null
            if (tcid) {
              // 优先按 tool_call_id 精确匹配
              for (let i = vm.stepEvents.length - 1; i >= 0; i--) {
                const s = vm.stepEvents[i]
                if (s.eventType === 'tool_call'
                    && s.stepData && s.stepData.tool_call_id === tcid) {
                  target = s
                  break
                }
              }
            }
            if (!target) {
              // 兜底：回退到最后一个 tool_call（异常/旧数据兼容）
              for (let i = vm.stepEvents.length - 1; i >= 0; i--) {
                const s = vm.stepEvents[i]
                if (s.eventType === 'tool_call') {
                  target = s
                  break
                }
              }
            }
            if (target) {
              target.elapsedSeconds = elapsed
            }
          } else if (data.eventType === 'skill_activated') {
            // 0724 改进三：技能激活事件——独立 step 展示"已激活技能：X"
            const snode = {
              eventType: 'skill_activated',
              stepNumber: stepNumber,
              phase: data.phase,
              stepData: data.stepData || {},
              label: data.label,
              stepId: data.stepId || null,
              parentStepId: data.parentStepId || (data.stepData && data.stepData.parent_step_id) || null,
              agentId: data.agentId || (data.stepData && data.stepData.agent_id) || null,
              thinkingCollapsed: false
            }
            vm.stepEvents.push(snode)
            vm.addToStepTree(snode)
          } else if (data.phase === 'answer') {
            // 最终回答（final 事件）：thinking step 内容由 flushTokenBuffer 实时写入，
            // streamingText 也同步更新，不做迁移——避免窗口消失和内容跳变
            const anode = {
              eventType: data.eventType,
              stepNumber: stepNumber,
              phase: data.phase,
              stepData: data.stepData || {},
              answer: data.answer,
              error: data.error,
              finalStep: data.finalStep,
              stepId: data.stepId || null,
              parentStepId: data.parentStepId || (data.stepData && data.stepData.parent_step_id) || null,
              agentId: data.agentId || (data.stepData && data.stepData.agent_id) || null,
              thinkingCollapsed: false
            }
            vm.stepEvents.push(anode)
            vm.addToStepTree(anode)
          } else {
            // tool_call / tool_result / error 事件：token 已由 flushTokenBuffer 实时回填
            // 0724 改进三：stepData 内带 tool_kind，供前端按分类展示
            const enode = {
              eventType: data.eventType,
              stepNumber: stepNumber,
              phase: data.phase,
              stepData: data.stepData || {},
              answer: data.answer,
              error: data.error,
              label: data.label,
              finalStep: data.finalStep,
              stepId: data.stepId || null,
              parentStepId: data.parentStepId || (data.stepData && data.stepData.parent_step_id) || null,
              agentId: data.agentId || (data.stepData && data.stepData.agent_id) || null,
              thinkingCollapsed: false
            }
            vm.stepEvents.push(enode)
            vm.addToStepTree(enode)

            // 0724 改进一方案A：workflow_end 已删，workflow 工具完成时由 tool_result(is_workflow=true) 触发
            // 将所有未提交的 clarify 步骤标记为过期（超时或已跳过）
            if (data.eventType === 'tool_result'
                && data.stepData && data.stepData.is_workflow) {
              vm.expirePendingClarifications()
            }
          }

          vm.$nextTick(() => {
            vm.scrollStreamingToBottom()
            vm.scrollToBottom()
          })
        },
        onStream(data) {
          if (!vm.isStreaming) {
            vm.isStreaming = true
          }
          vm.currentStreamStepNumber = data.stepNumber || 0
          // token batching — 30ms 批量 flush，按 type 分流
          const tokenType = data.type || 'answer'
          if (tokenType === 'thinking') {
            vm.thinkingBuffer += data.token
          } else {
            vm.tokenBuffer += data.token
          }
          if (!vm.flushTimer) {
            vm.flushTimer = setTimeout(() => vm.flushTokenBuffer(), 30)
          }
        },
        onResult(data) {
          vm.isStreaming = false
          // 流式结束：用原文整体渲染一次最终版 markdown，覆盖流式期间的中间态补全
          vm.streamingHtml = vm.formatAnswer(vm.streamingText)
          vm.stepCollapsed = true
          vm.answerCollapsed = false

          if (data.sessionId && !vm.activeSessionId) {
            chatSessionStore.setActiveSession(data.sessionId)
            vm.fetchSessions()
          }
          chatTreeStore.clearBranch()

          const userMsg = {
            id: -Date.now(),
            userId: null,
            sessionId: data.sessionId,
            parentId: parentMessageId,
            role: 'user',
            content: q,
            sources: '[]',
            createdAt: new Date().toISOString()
          }

          const assistantMsg = {
            id: data.messageId || -Date.now() + 1,
            userId: null,
            sessionId: data.sessionId,
            parentId: userMsg.id,
            role: 'assistant',
            content: data.answer,
            sources: JSON.stringify(data.sourceDetails || []),
            createdAt: new Date().toISOString(),
            stepEvents: [...vm.stepEvents]
          }

          vm.messagePanelState[assistantMsg.id] = { step: true, answer: false }

          chatTreeStore.state.messages.push(userMsg, assistantMsg)
          chatTreeStore.setActiveLeaf(assistantMsg.id)

          vm.$nextTick(() => vm.scrollToBottom())
          // 回答结束：无论流式期间用户是否上划查看过历史，"回答"窗口主动跳到最底部
          // 展示完整内容（kimi/智谱清言/deepseek 完成后自动滚到底的体验）。
          // 由于此时模板尚未把 streamingText 切回 formatAnswer 渲染，需在下一帧再次拉到底。
          vm.answerPinned = true
          vm.$nextTick(() => {
            if (vm.$refs.streamingAnswerBody) {
              vm.$refs.streamingAnswerBody.scrollTop = vm.$refs.streamingAnswerBody.scrollHeight
            }
          })
          // 流式已完成、消息已追加，恢复 watch 自动 load 能力
          vm.suppressWatchLoad = false
        },
        onError(data) {
          vm.tempUserMsg = null
          vm.loading = false
          vm.suppressWatchLoad = false
          vm.resetStreamState()
          vm.stepEvents.push({
            eventType: 'error',
            error: data.message || '未知错误',
            finalStep: true
          })
          chatTreeStore.state.messages.push({
            id: -Date.now(),
            role: 'assistant',
            content: '抱歉，发生了错误: ' + (data.message || '未知错误'),
            sources: '[]',
            parentId: null
          })
          vm.$nextTick(() => vm.scrollToBottom())
        },
        onDone() {
          vm.tempUserMsg = null
          vm.loading = false
          // 兜底：流式结束前把所有未闭合 step 标记完成，防止断连/异常导致永久转圈
          // （isStreaming=false 已能让 isStepRunning 返回 false，此标记为双保险）
          vm.markAllStepsCompleted()
          vm.isStreaming = false
          vm.$nextTick(() => {
            // 流式彻底结束后兜底再贴底一次：onResult 的滚动可能早于 DOM 完全渲染完成
            if (vm.$refs.streamingAnswerBody) {
              vm.$refs.streamingAnswerBody.scrollTop = vm.$refs.streamingAnswerBody.scrollHeight
            }
            if (vm.$refs.streamingStepBody) {
              vm.$refs.streamingStepBody.scrollTop = vm.$refs.streamingStepBody.scrollHeight
            }
            vm.scrollToBottom()
          })
          vm.fetchSessions()
          // 首轮回答完成后触发 AI 自动命名（KIMI 风格）
          if (needsAutoTitle && vm.activeSessionId) {
            chatSessionStore.autoTitle(vm.activeSessionId)
          }
        }
      })
    },

    getStepClass(step) {
      switch (step.eventType) {
        case 'tool_call': return 'step-tool'
        case 'tool_result': return 'step-tool'
        case 'thinking': return 'step-thinking'
        case 'error': return 'step-error'
        case 'final': return 'step-final'
        case 'skill_activated': return 'step-skill'
        case 'sub_agent': return 'step-sub-agent'
        default: return ''
      }
    },

    getStepIcon(step) {
      switch (step.eventType) {
        case 'tool_call': return 'Tools'
        case 'tool_result': return 'Document'
        case 'thinking': return 'MagicStick'
        case 'error': return 'CircleCloseFilled'
        case 'final': return 'CircleCheckFilled'
        case 'skill_activated': return 'MagicStick'
        case 'sub_agent': return 'Cpu'
        default: return 'LocationFilled'
      }
    },

    /**
     * 判断某个步骤是否正处于"执行中"状态，用于显示四芒星动画。
     * 统一规则：tool_call 与 sub_agent 一视同仁——触发即转，结束（收到 tool_result / sub_agent end）即停。
     * 不区分母/子、Agent/Tool，无特例。
     *
     * 计时由数据驱动：母 tool_call 有 tool_progress 心跳（elapsedSeconds 非空）则显示"已 N 秒"；
     * sub_agent 与子 Agent 内部工具无心跳源，纯转圈。
     *
     * 闭合判定：addToStepTree 在收到 tool_result / sub_agent end 时把对应节点标记 completed=true，
     * 此处据此停止动画——不再依赖"是否最后一个 step"（旧逻辑导致母 tool_call 在子 step 出现后丢动画、
     * sub_agent 永远不是最后一个而拿不到动画）。
     *
     * 超时软上限：tool_progress 心跳累计 > 660s 视为异常（后端可能已断连/超时但前端未收到结束事件），
     * 停止动画。仅对有心跳的母 tool_call 生效（elapsedSeconds 为 null 时不启用）。
     */
    isStepRunning(step, steps, isStreaming) {
      if (!isStreaming) return false
      if (!step) return false
      // 已闭合（tool_result / sub_agent end 已到达）——停
      if (step.completed === true) return false
      if (step.eventType === 'tool_call') {
        if (step.elapsedSeconds != null && step.elapsedSeconds > 660) return false
        return true
      }
      if (step.eventType === 'sub_agent') {
        // clarify 子 Agent 在等用户输入，非执行中——不转
        if (this.isClarifyStep(step)) return false
        return true
      }
      return false
    },

    getStepsForMessage(item) {
      if (item.stepEvents && item.stepEvents.length) {
        return item.stepEvents
      }
      const cached = this.historyStepsCache[item.id]
      if (cached && cached.length) {
        return cached
      }
      return []
    },

    /**
     * 判断是否为 HumanInTheLoop 澄清系列步骤（clarify 提问 / clarify_answer 回复）。
     * 两者同为 sub_agent 类型，须在模板 v-if 链中先于 sub_agent 面板分支判定，
     * 否则会被渲染成"无子步骤"空面板。是否有 question 决定显示输入框还是状态反馈。
     */
    isClarifyStep(step) {
      return step.eventType === 'sub_agent'
        && step.stepData
        && (step.stepData.agent_role === 'clarify'
            || step.stepData.agent_role === 'clarify_answer')
    },

    /**
     * 获取澄清输入框状态，不存在时初始化
     */
    getClarifyState(idx) {
      if (!this.clarifyInputs[idx]) {
        this.clarifyInputs = {
          ...this.clarifyInputs,
          [idx]: {
            answer: '',
            submitting: false,
            submitted: false,
            expired: false
          }
        }
      }
      return this.clarifyInputs[idx]
    },

    setClarifyAnswer(idx, value) {
      this.getClarifyState(idx).answer = value
    },

    /**
     * 工作流结束时，将所有未提交的澄清步骤标记为过期（超时或已跳过），
     * 禁用输入框并提示用户工作流已继续。
     */
    expirePendingClarifications() {
      const updated = { ...this.clarifyInputs }
      let changed = false
      this.stepEvents.forEach((step, idx) => {
        if (this.isClarifyStep(step) && updated[idx] && !updated[idx].submitted) {
          updated[idx] = { ...updated[idx], expired: true }
          changed = true
        }
      })
      if (changed) {
        this.clarifyInputs = updated
      }
    },

    /**
     * 提交 HumanInTheLoop 澄清回复
     */
    async submitClarify(idx) {
      const state = this.getClarifyState(idx)
      const answer = (state.answer || '').trim()
      if (!answer || state.submitting) return

      if (!this.activeSessionId) {
        console.warn('[Clarify] 无活跃会话，无法提交回复')
        return
      }

      state.submitting = true
      try {
        const res = await workflowApi.submitClarification(this.activeSessionId, answer)
        const completed = res.data && res.data.data && res.data.data.completed
        if (completed) {
          state.submitted = true
        } else {
          // 后端未找到 pending 请求（可能已超时或 sessionId 不匹配），
          // 不标记 submitted，允许用户重试或等待工作流结束
          const msg = (res.data && res.data.data && res.data.data.message) || '该请求已超时，请稍后重试'
          console.warn('[Clarify]', msg)
          alert(msg)
        }
      } catch (e) {
        console.error('[Clarify] 提交澄清回复失败:', e)
        alert('提交失败：' + (e.response?.data?.msg || e.message))
      } finally {
        state.submitting = false
      }
    },

    async loadHistorySteps(messageId) {
      this.loadingSteps[messageId] = true
      try {
        const res = await chatSessionApi.getMessageSteps(messageId)
        const steps = res.data.data || res.data || []
        const mapped = steps.map(s => ({
          id: s.id,
          eventType: s.stepType,
          stepNumber: s.stepOrder,
          stepData: s.stepData || {},
          content: s.content,
          // 0724 改进四：层级字段，供 buildStepTree 重建树
          parentStepId: s.parentStepId || s.parent_step_id || null,
          agentId: s.agentId || s.agent_id || null,
          thinkingContent: s.stepType === 'thinking' ? s.content : ''
        }))
        // 0724 改进四：历史步骤重建为树（按 parentStepId 组装 children）
        this.historyStepsCache[messageId] = this.buildStepTree(mapped)
      } catch (e) {
        console.error('加载推理步骤失败:', e)
      } finally {
        this.loadingSteps[messageId] = false
      }
    },

    /**
     * 0724 改造C：流式实时归集——onStep 收到带 stepId/parentStepId 的事件时挂入树。
     * - sub_agent start（is_start=true）：创建容器节点入树（根或挂 parent），记入 subAgentStartsByAgent
     * - sub_agent end（is_start=false）：合并到同 agentId 的 start（更新 success/error），不独立入树
     * - 有 parentStepId 且 nodeMap 命中：挂到 parent.children
     * - 否则：入根层 stepTree
     * thinking/tool_progress 等无 stepId 的事件直接入根层（兼容旧逻辑）。
     */
    addToStepTree(node) {
      const aid = node.agentId || (node.stepData && node.stepData.agent_name)
      // 修复：clarify 系列（clarify 提问 / clarify_answer 回复）是用户交互叶子节点，
      // 不参与 sub_agent start/end 配对——否则 clarify_answer 会被误当作 start 覆盖 clarify 节点，
      // 或两者被合并成一环。直接跳过配对逻辑，落到下方"强制入根层"分支。
      const role = node.stepData && node.stepData.agent_role
      const isClarifySeries = node.eventType === 'sub_agent'
        && (role === 'clarify' || role === 'clarify_answer')
      if (node.eventType === 'sub_agent' && node.stepData && !isClarifySeries) {
        const isEnd = node.stepData.is_start === false
          || node.stepData.is_start === 'false' || node.stepData.is_start === 0
        if (isEnd) {
          const startNode = aid ? this.subAgentStartsByAgent[aid] : null
          if (startNode) {
            startNode.stepData = { ...startNode.stepData, ...node.stepData }
            startNode.stepData.is_start = true
            if (node.error) startNode.error = node.error
            // 标记 sub_agent 已闭合：end 已到达，isStepRunning 据此停止动画
            startNode.completed = true
            delete this.subAgentStartsByAgent[aid]
          } else {
            // 无配对 start，作为根层保留
            this.stepTree.push(node)
            if (node.stepId != null) this.stepNodeMap[node.stepId] = node
          }
          return
        }
        // sub_agent start：记入配对表，标记未闭合
        if (aid && (node.stepData.is_start === true
            || node.stepData.is_start === 'true' || node.stepData.is_start === 1
            || node.stepData.is_start == null)) {
          this.subAgentStartsByAgent[aid] = node
          node.completed = false
        }
      }
      // tool_result 到达：按 tool_call_id 反查对应 tool_call，标记其已闭合
      if (node.eventType === 'tool_result' && node.stepData && node.stepData.tool_call_id) {
        const toolCallNode = this._findStepByToolCallId(node.stepData.tool_call_id)
        if (toolCallNode) toolCallNode.completed = true
      }
      // tool_call 入树时显式标记未闭合（配合 isStepRunning）
      if (node.eventType === 'tool_call') {
        node.completed = false
      }
      // 挂到 parent 或入根层
      // 0724 改造C：clarify 系列（clarify 提问 + clarify_answer 回复/超时/中断）强制入根层——
      // 它们是用户交互节点，需在根层可见：clarify 渲染输入框，clarify_answer 渲染状态反馈。
      // 若挂到 tool_call.children 下模板遍历不到，输入框与状态反馈都会消失，用户无法交互也无法感知结果。
      // isClarifySeries 已在方法开头计算（跳过配对时复用）。
      const pid = isClarifySeries ? null : node.parentStepId
      if (pid != null && this.stepNodeMap[pid]) {
        this.stepNodeMap[pid].children = this.stepNodeMap[pid].children || []
        this.stepNodeMap[pid].children.push(node)
      } else {
        this.stepTree.push(node)
      }
      if (node.stepId != null) this.stepNodeMap[node.stepId] = node
    },
    /**
     * 在 stepTree 全树（含各节点 children）按 tool_call_id 查找 tool_call 节点。
     * 供 tool_result 到达时反查对应 tool_call 标记 completed。
     */
    _findStepByToolCallId(tcid) {
      const walk = (list) => {
        for (const s of list) {
          if (s.eventType === 'tool_call'
              && s.stepData && s.stepData.tool_call_id === tcid) return s
          if (s.children) {
            const r = walk(s.children)
            if (r) return r
          }
        }
        return null
      }
      return walk(this.stepTree)
    },
    /**
     * 0724 改造C：重置流式树状态（新会话/新问题时调用）。
     */
    resetStepTree() {
      this.stepTree = []
      this.stepNodeMap = {}
      this.subAgentStartsByAgent = {}
    },
    /**
     * 标记所有 step 为已闭合（completed=true），递归含 children。
     * 流式结束兜底用：防止断连/异常导致未收到 tool_result / sub_agent end 的步骤永久转圈。
     */
    markAllStepsCompleted() {
      const walk = (list) => {
        list.forEach(s => {
          s.completed = true
          if (s.children) walk(s.children)
        })
      }
      walk(this.stepTree)
      this.stepEvents.forEach(s => { s.completed = true })
    },

    /**
     * 0724 改进四：将扁平 step 列表按 parentStepId 重建为树。
     * - 根层（parentStepId 为 null）放入结果数组
     * - 有 parent 的挂到对应 parent 的 children 下
     * - sub_agent 的 start/end 配对：end 事件（is_start=false）合并到同 agent 的 start 事件上（更新 success/error），
     *   不单独作为树节点，避免重复
     * 顺序场景下按 stepOrder 排序保证时序正确。
     */
    buildStepTree(steps) {
      if (!steps || !steps.length) return []
      // 按 stepOrder 排序保证时序（loadHistorySteps 已把 stepOrder 映射为 stepNumber）
      const sorted = [...steps].sort((a, b) => (a.stepNumber || 0) - (b.stepNumber || 0))
      // 建 id → node 映射（带 children）
      const nodeMap = new Map()
      sorted.forEach(s => {
        nodeMap.set(s.id, { ...s, children: [] })
      })
      const roots = []
      // 0724 改造C：is_start 宽松判定——兼容 jsonb 反序列化为字符串 "true"/"false" 的情况
      const isStart = (v) => v === true || v === 'true' || v === 1
      const isEnd = (v) => v === false || v === 'false' || v === 0
      // 第一遍：收集 sub_agent start 节点（用于配对 end）
      const subAgentStarts = new Map() // agentId → start node
      nodeMap.forEach(node => {
        if (node.eventType === 'sub_agent' && node.stepData
            && isStart(node.stepData.is_start)) {
          const aid = node.agentId || (node.stepData.agent_name)
          if (aid) subAgentStarts.set(aid, node)
        }
      })
      // 第二遍：构建树，end 事件合并到 start
      nodeMap.forEach(node => {
        // sub_agent end 事件：合并到同 agent 的 start，不独立入树
        if (node.eventType === 'sub_agent' && node.stepData
            && isEnd(node.stepData.is_start)) {
          const aid = node.agentId || (node.stepData.agent_name)
          let startNode = aid ? subAgentStarts.get(aid) : null
          // 兜底：is_start 字段缺失时，按同 agent_id 的首尾配对——此节点为 end，
          // 找同 agent_id 中尚未配对的 start（subAgentStarts 已存首个 start）
          if (!startNode && aid) {
            // 若 start 未被收集（is_start 缺失），扫一遍找同 agent 的首个 sub_agent
            for (const n of nodeMap.values()) {
              if (n.eventType === 'sub_agent'
                  && (n.agentId === aid || (n.stepData && n.stepData.agent_name === aid))
                  && n !== node) {
                startNode = n
                subAgentStarts.set(aid, n)
                break
              }
            }
          }
          if (startNode) {
            // 用 end 事件更新 start 的 success 状态
            startNode.stepData = { ...startNode.stepData, ...node.stepData }
            startNode.stepData.is_start = true // 保持为 start 节点（前端据此渲染面板）
            if (node.error) startNode.error = node.error
          } else {
            // 既无 start 也无配对，作为根层节点保留（不丢数据）
            roots.push(node)
          }
          return
        }
        // 跳过已作为 start 被合并引用的 end 节点（上面分支已 return）
        const pid = node.parentStepId
        if (pid != null && nodeMap.has(pid)) {
          nodeMap.get(pid).children.push(node)
        } else {
          roots.push(node)
        }
      })
      return roots
    },

    toggleHistoryStepsPanel(item) {
      //先切换折叠状态
      if (!this.messagePanelState[item.id]) {
        this.messagePanelState[item.id] = { step: true, answer: false }
      }
      const wasCollapsed = this.messagePanelState[item.id].step
      this.messagePanelState[item.id].step = !wasCollapsed

      //展开时且尚未加载步骤数据，触发懒加载
      if (wasCollapsed && !this.historyStepsCache[item.id] && !this.loadingSteps[item.id]) {
        this.loadHistorySteps(item.id)
      }
    },

    formatStepText(step) {
      const sd = step.stepData || {}
      const label = step.label || sd.display_label || null
      switch (step.eventType) {
        case 'tool_call': {
          // 0724 改进三：按 tool_kind 细化展示
          const kind = sd.tool_kind
          if (kind === 'skill') {
            return label || '加载技能'
          }
          if (kind === 'workflow') {
            return label || sd.tool_name || '执行工作流'
          }
          return label || sd.tool_name || '调用工具'
        }
        case 'tool_result': {
          const toolLabel = label || sd.tool_name || '工具'
          return sd.is_success === false ? `${toolLabel}失败` : `${toolLabel}完成`
        }
        case 'thinking':
          return label || (step.thinkingContent ? '思考详情' : '正在思考...')
        case 'error':
          return label || `出错：${step.error || '未知'}`
        case 'final':
          return label || '回答已就绪'
        case 'skill_activated': {
          // 0724 改进三：技能激活展示名 + 关联工具数
          const skillName = sd.skill_name || label || '技能'
          const toolNames = sd.tool_names || []
          const toolPart = toolNames.length ? `（关联 ${toolNames.length} 个工具）` : ''
          return label || `已激活技能：${skillName}${toolPart}`
        }
        case 'sub_agent': {
          if (label) {
            const success = sd.success !== undefined ? sd.success : true
            const question = sd.question
            const detail = step.answer || step.content || step.error
            if (question) {
              return `${label}：${question}`
            }
            if (detail) {
              return `${label}${success ? '' : '（失败）'}：${detail}`
            }
            return label + (success ? '' : '（失败）')
          }
          // 兜底：兼容旧数据或异常场景
          const role = sd.agent_role || ''
          const triggered = sd.triggered !== undefined ? sd.triggered : true
          const success = sd.success !== undefined ? sd.success : true
          const roleMap = {
            clarify: '需要你补充一些信息',
            clarify_answer: '已收到你的补充信息',
            knowledge_search: '正在收集资料',
            plan: '正在生成学习计划',
            plan_save: '正在保存学习计划',
            exam: '正在生成测验',
            exam_save: '正在保存测验'
          }
          const roleLabel = roleMap[role] || role
          if (!triggered) {
            return roleLabel
          }
          if (sd.question) {
            return `${roleLabel}：${sd.question}`
          }
          const detail = step.answer || step.content || step.error
          if (detail) {
            return `${roleLabel}${success ? '' : '（失败）'}：${detail}`
          }
          return roleLabel + (success ? '' : '（失败）')
        }
        default:
          return label || step.eventType || ''
      }
    },

    flushTokenBuffer() {
      if (this.thinkingBuffer || this.tokenBuffer) {
        const sn = this.currentStreamStepNumber
        if (!this.tokenGroups[sn]) {
          this.tokenGroups[sn] = ''
        }
        // 统一记录到 tokenGroups（向后兼容）
        if (this.thinkingBuffer) {
          this.tokenGroups[sn] += this.thinkingBuffer
        }
        if (this.tokenBuffer) {
          this.tokenGroups[sn] += this.tokenBuffer
        }
        // thinking token: 仅写入思考窗口，不污染回答面板
        if (this.thinkingBuffer) {
          const thinkingStep = this.stepEvents.find(
            s => s.stepNumber === sn && s.eventType === 'thinking'
          )
          if (thinkingStep) {
            thinkingStep.thinkingContent += this.thinkingBuffer
          }
          this.thinkingBuffer = ''
        }
        // answer token: 仅写入回答面板，不写入思考窗口（避免与推理内容重复）
        if (this.tokenBuffer) {
          this.streamingText += this.tokenBuffer
          this.tokenBuffer = ''
          // 调度节流式 markdown 实时渲染（60ms 节拍），与 token 30ms flush 解耦
          this.scheduleStreamingRender()
        }
        this.$nextTick(() => {
          this.scrollStreamingToBottom()
          this.scrollToBottom()
        })
      }
      this.flushTimer = null
    },

    /**
     * 节流式 markdown 实时渲染：流式期间按固定节拍把 streamingText 重新解析为 HTML，
     * 避免每个 token 触发一次 markdown-it 解析造成的卡顿。第一个 token 立即渲染一次，
     * 后续最多每 60ms 渲染一次。
     */
    scheduleStreamingRender() {
      // 首帧立即渲染，让用户尽早看到格式化效果
      if (this.streamingHtml === '') {
        this.streamingHtml = this.renderStreamingMarkdown(this.streamingText)
      }
      if (this.renderTimer) return
      this.renderTimer = setTimeout(() => {
        this.renderTimer = null
        this.streamingHtml = this.renderStreamingMarkdown(this.streamingText)
      }, 60)
    },

    /**
     * 对流式中途的半成品 markdown 做轻量补全，再交给 markdown-it 渲染：
     * - 未闭合的 ``` 代码块补一个闭合围栏，避免后续所有行被当成代码块
     * - 未闭合的行内 ` 补一个，避免跨段落渲染异常
     * 仅作用于渲染输入，不修改 streamingText 原文，结束后 onResult 用原文整体渲染一次。
     */
    renderStreamingMarkdown(text) {
      if (!text) return ''
      let src = text
      // 统计未闭合的代码围栏 ``` 数量，奇数则补一个闭合围栏
      const fenceCount = (src.match(/```/g) || []).length
      if (fenceCount % 2 === 1) {
        src += '\n```'
      }
      // 行内反引号未闭合补一个
      const inlineTickCount = (src.match(/`/g) || []).length
      if (inlineTickCount % 2 === 1) {
        src += '`'
      }
      return this.formatAnswer(src)
    },

    isPanelCollapsed(messageId, panelType) {
      const state = this.messagePanelState[messageId]
      if (!state) return panelType !== 'answer' // answer默认展开，其余默认折叠
      return state[panelType]
    },

    togglePanel(messageId, panelType) {
      if (!this.messagePanelState[messageId]) {
        this.messagePanelState[messageId] = { step: true, answer: false }
      }
      this.messagePanelState[messageId][panelType] = !this.messagePanelState[messageId][panelType]
    },

    branchFrom(messageId) {
      chatTreeStore.setBranchParent(messageId)
      this.question = ''
      this.$nextTick(() => {
        const textarea = this.$el.querySelector('.chat-input textarea')
        if (textarea) textarea.focus()
      })
    },
    cancelBranch() {
      chatTreeStore.clearBranch()
      this.question = ''
    },
    async handleDeleteSubtree(messageId) {
      if (!confirm('确定要删除此消息及其所有回复吗？')) return
      try {
        await chatSessionApi.deleteSubtree(messageId)
        const currentActiveLeaf = chatTreeStore.state.activeLeafId
        let newActiveLeaf = null
        if (currentActiveLeaf === messageId || chatTreeStore.isDescendantOf(messageId, currentActiveLeaf)) {
          newActiveLeaf = chatTreeStore.findNewActiveLeaf(messageId)
          chatTreeStore.setActiveLeaf(newActiveLeaf)
        } else {
          newActiveLeaf = currentActiveLeaf
        }
        await this.loadMessages(newActiveLeaf)
      } catch (e) {
        console.error('删除消息子树失败:', e)
        alert('删除失败: ' + (e.response?.data?.msg || e.message))
      }
    },
    handleTreeSelect(nodeId) {
      const leafId = chatTreeStore.findLeafDescendant(nodeId)
      chatTreeStore.setActiveLeaf(leafId)
      this.showTreeModal = false
      this.$nextTick(() => this.scrollToMessage(nodeId))
    },
    reAsk(messageId, content) {
      chatTreeStore.setBranchParent(messageId)
      this.question = content
      this.$nextTick(() => {
        const textarea = this.$el.querySelector('.chat-input textarea')
        if (textarea) {
          textarea.focus()
          textarea.setSelectionRange(textarea.value.length, textarea.value.length)
        }
      })
    },
    parseSourceDetails(sourcesStr) {
      try {
        return JSON.parse(sourcesStr || '[]')
      } catch {
        return []
      }
    },
    openChunkContext(sourceDetail) {
      if (!sourceDetail || !sourceDetail.chunkId) return
      this.showContextPanel = true
      this.$nextTick(() => {
        if (this.$refs.contextPanel) {
          this.$refs.contextPanel.loadContext(sourceDetail.chunkId)
        }
      })
    },
    handleNavigate(chunkId) {
      this.$nextTick(() => {
        if (this.$refs.contextPanel) {
          this.$refs.contextPanel.loadContext(chunkId)
        }
      })
    },
    formatAnswer(text) {
      if (!text) return ''
      // 先把内部跳转链接替换为占位符，躲过 markdown 转义
      let masked = text.replace(
        /\[([^\]]+)\]\(\/quiz\/(\d+)\)/g,
        (_, label, id) => EXAM_LINK_PLACEHOLDER(id, label)
      )
      masked = masked.replace(
        /\[([^\]]+)\]\(\/study-plan\?planId=(\d+)\)/g,
        (_, label, id) => PLAN_LINK_PLACEHOLDER(id, label)
      )
      // markdown 渲染
      let html = renderToHtml(masked)
      // 还原为可点击 span
      html = html.replace(
        EXAM_LINK_RE,
        (_, id, label) =>
          `<span class="exam-link" data-exam-id="${id}" onclick="window.__examLinkClick&&window.__examLinkClick(${id})">${label}</span>`
      )
      html = html.replace(
        PLAN_LINK_RE,
        (_, id, label) =>
          `<span class="exam-link" data-plan-id="${id}" onclick="window.__studyPlanLinkClick&&window.__studyPlanLinkClick(${id})">${label}</span>`
      )
      return html
    },
    scrollToBottom() {
      const container = this.$refs.messagesContainer
      if (container) {
        container.scrollTop = container.scrollHeight
      }
    },

    // 精确定位到指定消息节点：对话树跳转时使用，取代原来固定的滚到底部。
    // 借助 data-msg-id 属性在渲染列表中查找对应行；找不到则退化为滚到底部。
    scrollToMessage(msgId) {
      const container = this.$refs.messagesContainer
      if (!container) return
      const row = container.querySelector(`[data-msg-id="${msgId}"]`)
      if (row) {
        row.scrollIntoView({ behavior: 'smooth', block: 'start' })
      } else {
        container.scrollTop = container.scrollHeight
      }
    },

    // 判断某 DOM 容器是否接近底部（容差 48px），用于"贴底跟随"判定。
    // 流式追加内容时只有贴底的窗口才自动滚到底，用户上划查看历史时不打断。
    isNearBottom(el, threshold = 48) {
      if (!el) return true
      return el.scrollHeight - el.scrollTop - el.clientHeight < threshold
    },

    // 流式"思考过程"窗口滚动监听：用户上划离开底部时关闭跟随，
    // 滚回底部附近时恢复跟随。
    onStreamingStepScroll(e) {
      if (!this.isStreaming) return
      this.stepPinned = this.isNearBottom(e.target)
    },

    // 流式"回答"窗口滚动监听：同上，独立追踪。
    onStreamingAnswerScroll(e) {
      if (!this.isStreaming) return
      this.answerPinned = this.isNearBottom(e.target)
    },

    // 仅当对应窗口处于贴底状态时，把它的滚动位置拉到底部。
    // 用户上划查看历史内容时窗口视角保持不动（kimi/智谱清言/deepseek 风格）。
    scrollStreamingToBottom() {
      if (this.stepPinned && this.$refs.streamingStepBody) {
        this.$refs.streamingStepBody.scrollTop = this.$refs.streamingStepBody.scrollHeight
      }
      if (this.answerPinned && this.$refs.streamingAnswerBody) {
        this.$refs.streamingAnswerBody.scrollTop = this.$refs.streamingAnswerBody.scrollHeight
      }
    }
  }
}
</script>

<style scoped>
@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.streaming-cursor {
  display: inline-block;
  animation: blink 0.8s infinite;
  color: #b8763d;
  font-weight: bold;
  margin-left: 1px;
}

.streaming-answer {
  display: inline;
}

.chat-panel-wrapper {
  display: flex;
  height: 100%;
}

/* 聊天头部栏：标题 + 对话树按钮（右上角） */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  border-bottom: 1px solid #e8e2d4;
  background: #faf8f4;
  flex-shrink: 0;
}

.chat-header-title {
  font-size: 15px;
  font-weight: 500;
  color: #1a2e2a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  background: white;
  color: #b8763d;
  border: 1px solid #b8763d;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  font-family: inherit;
  white-space: nowrap;
  transition: all 0.2s;
  flex-shrink: 0;
}

.tree-btn:hover:not(:disabled) {
  background: #b8763d;
  color: white;
}

.tree-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
}

.welcome-hint {
  text-align: center;
  color: #999;
  padding: 48px 16px;
  font-size: 14px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f5f5f5;
  border-radius: 8px;
  margin-bottom: 16px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 8px;
  padding: 4px 8px;
  border-radius: 6px;
}

.message {
  flex: 1;
  min-width: 0;
}

/* 用户消息靠右排列，限制最大宽度（聊天软件风格） */
.message.user {
  flex: 0 1 70%;
  max-width: 70%;
  margin-left: auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.message-content {
  display: inline-block;
  max-width: 100%;
  padding: 10px 14px;
  border-radius: 12px;
  text-align: left;
}

.user-message {
  background: #b8763d;
  color: white;
  border-radius: 12px;
  padding: 10px 14px;
  word-break: break-word;
}

.message.assistant .message-content {
  display: block;
  width: 100%;
}

.bot-message {
  background: white;
  color: #333;
  box-shadow: 0 1px 2px rgba(0,0,0,0.1);
  width: 100%;
}

.agent-output {
  width: 100%;
}

.answer {
  margin-top: 4px;
  line-height: 1.6;
  /* 为有序/无序列表的序号/项目符号预留悬挂空间，避免 marker 被容器左边界裁切 */
  padding-left: 4px;
}

/* 列表 marker 悬挂在内容区之外，需额外左内边距容纳；
   outside 定位使序号/符号悬挂于内容块左侧，多行续行与首行正文对齐 */
.answer :deep(ol),
.answer :deep(ul) {
  padding-left: 1.6em;
  margin-left: 0.4em;
}

.answer :deep(li) {
  /* 防止超长 token 撑破宽度导致列表项溢出滚动条 */
  overflow-wrap: anywhere;
  word-break: break-word;
}

/* 代码块横向溢出走内部滚动，不顶破列表/容器 */
.answer :deep(pre) {
  overflow-x: auto;
}

.exam-link {
  display: inline-block;
  margin: 4px 2px;
  padding: 4px 12px;
  background: #b8763d;
  color: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: background 0.2s;
}

.exam-link:hover {
  background: #a0682f;
}

.sources {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #eee;
  font-size: 12px;
}

.source-label {
  color: #666;
  margin-right: 8px;
}

.source-tag {
  display: inline-block;
  background: #f3e6d4;
  color: #a0682f;
  padding: 2px 8px;
  border-radius: 4px;
  margin-right: 4px;
  font-size: 11px;
}

.source-tag.clickable {
  cursor: pointer;
  transition: all 0.15s;
}

.source-tag.clickable:hover {
  background: #ecd9b8;
  text-decoration: underline;
}

.loading {
  color: #666;
  font-style: italic;
}

.message-actions {
  margin-top: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}

/* 用户消息靠右排列时，操作按钮跟随靠右 */
.message.user .message-actions {
  text-align: right;
}

.message.user .message-actions .action-btn {
  margin-right: 0;
  margin-left: 4px;
}

.message:hover .message-actions {
  opacity: 1;
}

.action-btn {
  background: none;
  border: 1px solid #ddd;
  color: #666;
  padding: 1px 8px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 11px;
  margin-right: 4px;
}

.action-btn:hover {
  background: #f0f0f0;
  color: #333;
}

.action-delete:hover {
  border-color: #e53935;
  color: #e53935;
  background: #ffebee;
}

.action-cancel-branch {
  color: #e65100;
  border-color: #e65100;
}

.action-cancel-branch:hover {
  background: #fff3e0;
  color: #e65100;
  border-color: #e65100;
}

.collapsible-panel {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  margin-bottom: 8px;
  overflow: hidden;
  background: white;
  transition: all 0.25s ease;
}

.collapsible-panel.collapsed {
  background: #fafbfc;
}

.panel-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
  background: #f8f9fa;
  border-bottom: 1px solid #eee;
  transition: background 0.15s;
}

.collapsed > .panel-header {
  border-bottom: none;
}

.panel-header:hover {
  background: #eef1f5;
}

.panel-toggle {
  font-size: 11px;
  color: #888;
  flex-shrink: 0;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #444;
}

.panel-badge {
  font-size: 11px;
  color: #b8763d;
  background: #f7eede;
  padding: 1px 7px;
  border-radius: 10px;
  margin-left: auto;
}

.panel-body {
  padding: 10px 12px;
  max-height: 300px;
  overflow-y: auto;
}

.answer-panel .panel-body {
  max-height: 400px;
  line-height: 1.6;
}

.step-placeholder {
  color: #aaa;
  font-style: italic;
  font-size: 13px;
  text-align: center;
  padding: 12px 0;
}

.load-steps-btn {
  display: inline-block;
  margin-top: 8px;
  padding: 4px 12px;
  font-size: 12px;
  color: #6366f1;
  background: #f0f0ff;
  border: 1px solid #c7c7f7;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}
.load-steps-btn:hover {
  background: #e0e0ff;
}
.load-steps-btn.loading {
  color: #999;
  cursor: default;
  background: #f5f5f5;
  border-color: #ddd;
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 5px 0;
  font-size: 13px;
  line-height: 1.5;
  border-bottom: 1px solid #f5f5f5;
}

.step-item:last-child {
  border-bottom: none;
}

.step-icon {
  flex-shrink: 0;
  font-size: 14px;
  margin-top: 1px;
}

.step-text {
  word-break: break-word;
  color: #444;
}

.step-tool .step-text {
  color: #a0682f;
}

/* 工具执行中：图标转圈加载动画 + "执行中..." 提示 */
.step-icon.is-loading {
  animation: step-spin 1s linear infinite;
}

@keyframes step-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.step-running-hint {
  margin-left: 4px;
  font-size: 12px;
  color: #a0682f;
  font-style: italic;
}

.step-thinking .step-text {
  color: #666;
  font-style: italic;
}

.step-error .step-text {
  color: #c62828;
  font-weight: 500;
}

.step-final .step-text {
  color: #2e7d32;
  font-weight: 500;
}

.step-sub-agent .step-text {
  color: #00838f;
}

/* 0724 改进三：技能激活步骤样式 */
.step-skill .step-text {
  color: #6a1b9a;
  font-weight: 500;
}

/* 0724 改进四：sub_agent 可折叠子面板（历史回看树形） */
.sub-agent-panel {
  margin: 4px 0;
  background: #f1f8f9;
  border: 1px solid #b2dfdb;
  border-radius: 6px;
}

.sub-agent-panel > .panel-header {
  padding: 6px 8px;
}

.sub-agent-panel > .panel-body {
  padding: 4px 8px 4px 16px;
  border-left: 2px solid #b2dfdb;
  margin-left: 8px;
}

/* HumanInTheLoop 澄清步骤样式 */
.clarify-step {
  flex-direction: column;
  align-items: stretch;
  background: #f3e5f5;
  border: 1px solid #ce93d8;
  border-radius: 6px;
  padding: 10px;
  margin: 6px 0;
}

.clarify-step .step-icon {
  margin-bottom: 4px;
}

.clarify-content {
  flex: 1;
}

.clarify-input-area {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.clarify-input {
  width: 100%;
  padding: 8px;
  border: 1px solid #bdbdbd;
  border-radius: 4px;
  font-size: 13px;
  resize: vertical;
  font-family: inherit;
  box-sizing: border-box;
}

.clarify-input:focus {
  outline: none;
  border-color: #8e24aa;
  box-shadow: 0 0 0 2px rgba(142, 36, 170, 0.1);
}

.clarify-submit-btn {
  align-self: flex-start;
  padding: 6px 16px;
  background: #8e24aa;
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
}

.clarify-submit-btn:hover:not(:disabled) {
  background: #6a1b9a;
}

.clarify-submit-btn:disabled {
  background: #bdbdbd;
  cursor: not-allowed;
}

.clarify-status {
  margin-top: 8px;
  padding: 6px 10px;
  background: #e8f5e9;
  border: 1px solid #81c784;
  border-radius: 4px;
  font-size: 13px;
  color: #2e7d32;
}

.clarify-done {
  color: #2e7d32;
}

.clarify-expired {
  background: #fff3e0;
  border-color: #ffb74d;
  color: #e65100;
}

.thinking-content {
  white-space: pre-wrap;
  color: #666;
  font-style: italic;
  border-bottom: 1px solid #eee;
  padding-bottom: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.5;
}

.streaming-plain {
  white-space: pre-wrap;
}

/* 流式实时渲染的 markdown 容器：行内格式为主，不做 pre-wrap，否则会与 markdown 的换行冲突 */
.streaming-md {
  white-space: normal;
}

.sub-panel {
  margin-top: 6px;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  background: #fafafa;
}

.sub-panel .panel-header {
  padding: 4px 8px;
  font-size: 13px;
}

.sub-panel .panel-body {
  padding: 6px 8px;
}

.sub-panel .thinking-content {
  border-bottom: none;
  padding-bottom: 0;
  margin-bottom: 0;
}

.chat-input {
  display: flex;
  gap: 12px;
}

.chat-input textarea {
  flex: 1;
  padding: 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  resize: none;
  font-size: 14px;
}

.chat-input button {
  padding: 12px 24px;
  background: #b8763d;
  color: white;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  white-space: nowrap;
}

.chat-input button:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.chat-input .btn-send-branch {
  background: #e65100;
}

.chat-input .btn-send-branch:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.chat-input button.btn-stop {
  background: #e65100;
  cursor: pointer;
}

.chat-input button.btn-stop:hover {
  background: #d84315;
}

.stop-icon {
  display: inline-block;
  width: 14px;
  height: 14px;
  background: #fff;
  border-radius: 3px;
}

.context-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.context-modal {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 700px;
  height: 80vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  overflow: hidden;
  animation: slideUp 0.25s ease-out;
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.tree-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1001;
}

.tree-modal {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 960px;
  height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  overflow: hidden;
  animation: slideUp 0.25s ease-out;
}
</style>
