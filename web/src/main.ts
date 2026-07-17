import { computed, createApp, nextTick, onMounted, ref } from 'vue'
import './style.css'

/** 知识库归档中的一份文档。 */
type DocumentItem = {
  id: string
  title: string
  originalFilename: string
  fileType: string
  status: string
  failureReason?: string
}

/** 小C回答中引用的一条知识库资料。 */
type Citation = {
  documentId: string
  documentTitle: string
  chunkNo: number
  excerpt: string
}

/** 中间对话区展示的一条用户或小C消息。 */
type ChatMessage = {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  citations?: Citation[]
}

/** 左侧历史列表中保存的一次完整会话。 */
type Conversation = {
  id: string
  title: string
  npcStarted: boolean
  updatedAt: string
}

/** 用户在确认弹窗中暂存的删除对象。 */
type PendingDelete = {
  kind: 'conversation' | 'document'
  id: string
  title: string
}

const App = {
  setup() {
    /** 当前打开会话中小C是否已收到启动口令。 */
    const npcStarted = ref(false)

    /** 发送给后端的小C角色提示词，仅在启用 DeepSeek 增强回答时使用。 */
    const assistantPrompt = '你叫小C，是 NPC 的个人知识库助手。请使用中文，优先根据右侧归档资料回答；资料不足时如实说明。'

    /** 中间输入框中尚未发送的内容，可输入启动口令或正常问题。 */
    const question = ref('')

    /** 小C正在等待模型回答时禁用重复发送。 */
    const asking = ref(false)

    /** 对话区需要展示的网络或业务错误。 */
    const chatError = ref('')

    /** 等待用户确认删除的会话或资料。 */
    const pendingDelete = ref<PendingDelete | null>(null)

    /** 删除请求执行期间禁用弹窗按钮，避免重复提交。 */
    const deleting = ref(false)

    /** 对话区展示当前选中会话的消息序列。 */
    const messages = ref<ChatMessage[]>([])

    /** 后端数据库保存的历史会话，用于左栏展示与切换。 */
    const conversations = ref<Conversation[]>([])

    /** 当前正在查看的会话主键。 */
    const currentConversationId = ref('')

    /** 当前会话标题，用于中间栏头部标识正在进行的会话。 */
    const currentConversationTitle = computed(() => conversations.value.find(item => item.id === currentConversationId.value)?.title || '新对话')

    /** 当前模式对应的输入框占位提示，用于说明启动或关闭 DeepSeek 的口令。 */
    const composerPlaceholder = computed(() => npcStarted.value
      ? 'DeepSeek 已启用；继续提问，或输入“小c关闭”切回资料检索模式…'
      : '直接提问，或输入“小c启动”启用 DeepSeek 增强回答…')

    /** 当前模式对应的输入框辅助说明，避免用户误以为每次提问都会调用 DeepSeek。 */
    const composerHint = computed(() => npcStarted.value
      ? 'Enter 发送 · 输入“小c关闭”可停止调用 DeepSeek'
      : 'Enter 发送 · 输入“小c启动”后才调用 DeepSeek')

    /** 右侧归档区从后端读取的文档列表。 */
    const documents = ref<DocumentItem[]>([])

    /** 右侧上传区当前选中的资料文件。 */
    const file = ref<File | null>(null)

    /** 用户为上传文件填写的可选归档标题。 */
    const title = ref('')

    /** 文档上传或索引操作的执行状态。 */
    const uploading = ref(false)

    /** 右侧上传区展示的操作结果提示。 */
    const archiveMessage = ref('')

    /** 保存消息列表元素，用于每次追加消息后滚动到底部或定位回答。 */
    const messageList = ref<HTMLElement | null>(null)

    /** 用户是否仍停留在消息区底部附近；手动上翻历史消息后不会强制拉回最新回答。 */
    const shouldAutoFollowLatest = ref(true)

    /** 鼠标悬停或点击会话导航点后，是否展开用户提问前缀列表。 */
    const conversationNavigatorExpanded = ref(false)

    /** 用户点击某条提问后锁定导航浮层，避免鼠标离开时立即消失。 */
    const conversationNavigatorPinned = ref(false)

    /** 会话导航容器元素，用于以被点击圆点为锚点定位浮窗。 */
    const conversationNavigator = ref<HTMLElement | null>(null)

    /** 会话导航浮窗元素，用于避免浮窗在顶部或底部超出可视区域。 */
    const navigatorPreview = ref<HTMLElement | null>(null)

    /** 浮窗相对于右侧导航栏顶部的纵向锚点位置。 */
    const navigatorAnchorTop = ref(0)

    /** 鼠标从圆点移动到浮窗期间使用的延迟关闭计时器。 */
    let navigatorCloseTimer: number | undefined

    /** 当前阅读位置对应的最近一条用户提问，用于在导航列表中高亮。 */
    const activePromptId = ref('')

    /** 已渲染消息元素的引用，用于点击缩略块后准确跳转到对应消息。 */
    const messageElements = new Map<string, HTMLElement>()

    /** 当前会话中的用户提问列表；右侧导航只展示提问，不展示小C完整回答。 */
    const userMessages = computed(() => messages.value.filter(message => message.role === 'user'))

    /**
     * 将后端处理状态转换为资料列表中更容易理解的中文说明。
     *
     * @param status 文档在后端保存的处理状态码
     * @returns 面向用户展示的处理进度文案
     */
    function documentStatusText(status: string) {
      const textMap: Record<string, string> = {
        UPLOADED: '资料已上传，等待开始处理',
        PARSING: '正在读取并整理文档内容',
        INDEXING: '正在创建向量索引',
        INDEXED: '向量已生成，可以提问',
        FAILED: '处理失败，请查看原因'
      }
      return textMap[status] || '正在处理中'
    }

    /**
     * 调用后端接口并返回原始响应，接口未使用登录或令牌校验。
     *
     * @param path 后端接口路径
     * @param options fetch 请求配置
     * @returns 后端响应对象
     */
    async function request(path: string, options: RequestInit = {}) {
      return fetch(path, options)
    }

    /**
     * 从错误响应中提取后端消息，无法解析时返回默认文本。
     *
     * @param response 后端错误响应
     * @param fallback 默认错误文本
     * @returns 可直接展示的错误信息
     */
    async function errorOf(response: Response, fallback: string) {
      try {
        return (await response.json()).message || fallback
      } catch {
        return fallback
      }
    }

    /**
     * 关闭当前显示的会话错误弹窗。
     */
    function closeChatError() {
      chatError.value = ''
    }

    /**
     * 刷新右侧已归档资料，便于上传、删除和重建索引后同步状态。
     */
    async function refreshDocuments() {
      const response = await request('/api/documents')
      if (response.ok) {
        documents.value = (await response.json()).content || []
      }
    }

    /**
     * 保存用户选中的第一份文件，作为后续上传内容。
     *
     * @param event 文件选择框的 change 事件
     */
    function chooseFile(event: Event) {
      file.value = (event.target as HTMLInputElement).files?.[0] || null
    }

    /**
     * 上传资料文件并请求后端解析为可供小C参考的知识库切片。
     */
    async function upload() {
      if (!file.value) {
        return
      }
      uploading.value = true
      archiveMessage.value = ''
      const form = new FormData()
      form.append('file', file.value)
      if (title.value.trim()) {
        form.append('title', title.value.trim())
      }
      // 通过文档上传接口保存原始资料并创建异步解析任务。
      const response = await request('/api/documents/upload', { method: 'POST', body: form })
      uploading.value = false
      if (!response.ok) {
        archiveMessage.value = await errorOf(response, '资料上传失败')
        return
      }
      file.value = null
      title.value = ''
      archiveMessage.value = '资料已归档，后台正在解析并生成可引用内容。'
      await refreshDocuments()
    }

    /**
     * 删除指定资料及其已生成的知识库切片。
     *
     * @param id 待删除文档主键
     */
    async function removeDocument(id: string) {
      const document = documents.value.find(item => item.id === id)
      if (!document) {
        return
      }
      pendingDelete.value = { kind: 'document', id: document.id, title: document.title }
    }

    /**
     * 为已经归档的资料重新创建解析、BGE-M3 向量化和 Qdrant 入库任务。
     *
     * @param id 待重建索引的文档主键
     */
    async function reindexDocument(id: string) {
      const response = await request(`/api/documents/${id}/reindex`, { method: 'POST' })
      archiveMessage.value = response.ok
        ? '已加入处理队列，稍后刷新查看处理进度。'
        : await errorOf(response, '重新向量化失败')
      if (response.ok) {
        await refreshDocuments()
      }
    }

    /**
     * 将输入转为不受空格和大小写影响的启动口令。
     *
     * @param value 用户输入内容
     * @returns 规范化后的输入内容
     */
    function normalizeCommand(value: string) {
      return value.replace(/\s+/g, '').toLowerCase()
    }

    /**
     * 从后端读取数据库中的历史会话列表。
     */
    async function loadConversations() {
      const response = await request('/api/conversations')
      if (response.ok) {
        conversations.value = await response.json()
      }
    }

    /**
     * 创建数据库会话，并将其切换为当前对话。
     */
    async function startNewChat() {
      const response = await request('/api/conversations', { method: 'POST' })
      if (!response.ok) {
        chatError.value = await errorOf(response, '创建新会话失败')
        return
      }
      const conversation = await response.json() as Conversation
      conversations.value.unshift(conversation)
      await selectConversation(conversation)
    }

    /**
     * 切换到指定历史会话，并从后端加载其完整消息记录。
     *
     * @param conversation 待打开的历史会话
     */
    async function selectConversation(conversation: Conversation) {
      const response = await request(`/api/conversations/${conversation.id}/messages`)
      if (!response.ok) {
        chatError.value = await errorOf(response, '加载会话记录失败')
        return
      }
      currentConversationId.value = conversation.id
      npcStarted.value = conversation.npcStarted
      messages.value = await response.json()
      chatError.value = ''
      await scrollToLatestMessage()
    }

    /**
     * 删除指定会话及其数据库消息记录。
     *
     * @param conversation 待删除的历史会话
     */
    async function deleteConversation(conversation: Conversation) {
      pendingDelete.value = { kind: 'conversation', id: conversation.id, title: conversation.title }
    }

    /**
     * 关闭删除确认弹窗，不修改任何会话、资料或索引数据。
     */
    function closeDeleteModal() {
      if (!deleting.value) {
        pendingDelete.value = null
      }
    }

    /**
     * 根据弹窗中暂存的对象执行会话或资料删除，并在完成后同步页面数据。
     */
    async function confirmDelete() {
      const target = pendingDelete.value
      if (!target || deleting.value) {
        return
      }
      deleting.value = true
      if (target.kind === 'document') {
        const response = await request(`/api/documents/${target.id}`, { method: 'DELETE' })
        archiveMessage.value = response.ok ? '资料已删除。' : await errorOf(response, '删除资料失败')
        if (response.ok) {
          await refreshDocuments()
        }
      } else {
        const response = await request(`/api/conversations/${target.id}`, { method: 'DELETE' })
        if (!response.ok) {
          chatError.value = await errorOf(response, '删除会话失败')
        } else {
          conversations.value = conversations.value.filter(item => item.id !== target.id)
          if (target.id === currentConversationId.value) {
            if (conversations.value.length) {
              await selectConversation(conversations.value[0])
            } else {
              await startNewChat()
            }
          }
        }
      }
      deleting.value = false
      pendingDelete.value = null
    }

    /**
     * 判断消息区是否仍停留在底部附近，用于避免打断用户阅读历史消息。
     *
     * @returns 是否适合自动跟随最新消息
     */
    function isNearMessageListBottom() {
      const list = messageList.value
      if (!list) {
        return true
      }
      return list.scrollHeight - list.scrollTop - list.clientHeight < 88
    }

    /**
     * 处理消息区滚动，同时记录用户是否主动上翻查看历史消息。
     */
    function handleMessageListScroll() {
      shouldAutoFollowLatest.value = isNearMessageListBottom()
      updateActivePrompt()
    }

    /**
     * 将消息列表滚动到最新内容，确保新出现的“正在思考”不会被底部输入框挡住。
     */
    async function scrollToLatestMessage() {
      await nextTick()
      if (messageList.value) {
        shouldAutoFollowLatest.value = true
        messageList.value.scrollTop = messageList.value.scrollHeight
        updateActivePrompt()
      }
    }

    /**
     * 将一条新回答的开头对齐到消息区可读位置，避免长回答直接跳到结尾。
     *
     * @param messageId 需要展示开头的回答消息主键
     */
    async function scrollToAnswerStart(messageId: string) {
      await nextTick()
      const target = messageElements.get(messageId)
      const list = messageList.value
      if (!target || !list) {
        return
      }
      const targetTop = target.offsetTop - list.offsetTop - 18
      list.scrollTo({ top: Math.max(targetTop, 0), behavior: 'smooth' })
      window.setTimeout(updateActivePrompt, 350)
    }

    /**
     * 根据会话内容区的实际滚动位置，更新右侧提问导航中当前高亮的提问。
     */
    function updateActivePrompt() {
      if (!messageList.value) {
        return
      }
      const readingLine = messageList.value.scrollTop + messageList.value.clientHeight * 0.3
      let currentId = userMessages.value[0]?.id || ''
      for (const message of userMessages.value) {
        const element = messageElements.get(message.id)
        if (element && element.offsetTop - messageList.value.offsetTop <= readingLine) {
          currentId = message.id
        }
      }
      activePromptId.value = currentId
    }

    /**
     * 保存或移除每条消息对应的页面元素，供会话缩略导航跳转使用。
     *
     * @param messageId 消息主键
     * @param element Vue 渲染后提供的元素引用
     */
    function registerMessageElement(messageId: string, element: unknown) {
      if (element instanceof HTMLElement) {
        messageElements.set(messageId, element)
        return
      }
      messageElements.delete(messageId)
    }

    /** 清除右侧会话导航的延迟关闭计时器。 */
    function clearNavigatorCloseTimer() {
      if (navigatorCloseTimer !== undefined) {
        window.clearTimeout(navigatorCloseTimer)
        navigatorCloseTimer = undefined
      }
    }

    /**
     * 将浮窗限制在会话导航可见区域内；正常情况下与点击的圆点居中对齐。
     */
    function clampNavigatorPreviewPosition() {
      const navigator = conversationNavigator.value
      const preview = navigatorPreview.value
      if (!navigator || !preview) {
        return
      }
      const padding = 12
      const minTop = preview.offsetHeight / 2 + padding
      const maxTop = navigator.clientHeight - preview.offsetHeight / 2 - padding
      navigatorAnchorTop.value = minTop > maxTop
        ? navigator.clientHeight / 2
        : Math.min(Math.max(navigatorAnchorTop.value, minTop), maxTop)
    }

    /**
     * 根据鼠标所在圆点确定浮窗位置，并在渲染后校正上下边界。
     *
     * @param event 圆点触发的鼠标事件
     */
    function positionNavigatorPreview(event: MouseEvent) {
      const navigator = conversationNavigator.value
      const target = event.currentTarget
      if (!navigator || !(target instanceof HTMLElement)) {
        return
      }
      const navigatorRect = navigator.getBoundingClientRect()
      const dotRect = target.getBoundingClientRect()
      navigatorAnchorTop.value = dotRect.top - navigatorRect.top + dotRect.height / 2
      void nextTick(() => window.requestAnimationFrame(clampNavigatorPreviewPosition))
    }

    /**
     * 鼠标进入圆点或浮窗时保持浮窗显示，确保可顺畅移入左侧浮窗操作。
     */
    function keepConversationNavigatorOpen() {
      clearNavigatorCloseTimer()
      conversationNavigatorExpanded.value = true
    }

    /**
     * 鼠标临时悬停圆点时，在该圆点旁显示预览；离开后未锁定的预览会收起。
     *
     * @param event 圆点触发的鼠标事件
     */
    function previewNavigatorAtDot(event: MouseEvent) {
      keepConversationNavigatorOpen()
      positionNavigatorPreview(event)
    }

    /**
     * 鼠标离开圆点或浮窗时延迟收起，给鼠标跨越两者之间的空隙留出时间。
     */
    function scheduleConversationNavigatorClose() {
      clearNavigatorCloseTimer()
      navigatorCloseTimer = window.setTimeout(() => {
        if (!conversationNavigatorPinned.value) {
          conversationNavigatorExpanded.value = false
        }
      }, 180)
    }

    /**
     * 在中间会话区域继续阅读时解除导航浮层锁定，恢复鼠标悬停才展示的交互。
     */
    function releaseConversationNavigator() {
      clearNavigatorCloseTimer()
      conversationNavigatorPinned.value = false
      conversationNavigatorExpanded.value = false
    }

    /**
     * 滚动到用户在会话缩略预览中选中的一条消息。
     *
     * @param messageId 目标消息主键
     */
    function scrollToMessage(messageId: string) {
      const target = messageElements.get(messageId)
      if (!target || !messageList.value) {
        return
      }
      const targetTop = target.offsetTop - messageList.value.offsetTop - 20
      messageList.value.scrollTo({ top: Math.max(targetTop, 0), behavior: 'smooth' })
      window.setTimeout(updateActivePrompt, 350)
    }

    /**
     * 点击右侧圆点或提问前缀后定位到目标消息，并锁定导航浮层供用户继续查看。
     *
     * @param messageId 目标用户提问的消息主键
     * @param event 点击圆点时用于计算浮窗锚点的鼠标事件；点击浮窗条目时无需更新锚点
     */
    function selectPromptFromNavigator(messageId: string, event?: MouseEvent) {
      clearNavigatorCloseTimer()
      conversationNavigatorPinned.value = true
      conversationNavigatorExpanded.value = true
      if (event?.currentTarget instanceof HTMLElement && event.currentTarget.classList.contains('navigator-dot')) {
        positionNavigatorPreview(event)
      }
      scrollToMessage(messageId)
    }

    /**
     * 将用户提问压缩成单行前缀，供右侧会话导航浮层展示。
     *
     * @param content 用户提问原文
     * @returns 去除换行和多余空白后的单行提问
     */
    function questionPreview(content: string) {
      return content.replace(/\s+/g, ' ').trim()
    }

    /**
     * 提交启动口令或问题；默认使用本地资料模式，启动小C后才请求 DeepSeek。
     */
    async function send() {
      const content = question.value.trim()
      if (!content || asking.value) {
        return
      }
      chatError.value = ''
      question.value = ''
      if (!currentConversationId.value) {
        await startNewChat()
      }
      const conversationId = currentConversationId.value
      const pendingMessage: ChatMessage = {
        id: `pending-${Date.now()}`,
        role: 'user',
        content
      }
      // 先在界面中显示用户提问，避免检索或模型回答期间提问气泡消失。
      messages.value.push(pendingMessage)
      await scrollToLatestMessage()
      asking.value = true
      // “正在思考”消息刚插入后再次滚动，确保它完整显示在底部输入框上方。
      await scrollToLatestMessage()
      try {
        // 后端保存用户输入，未启动时使用本地资料模式，启动后调用 DeepSeek。
        const response = await request(`/api/conversations/${conversationId}/messages`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ question: content, assistantPrompt })
        })
        if (!response.ok) {
          chatError.value = await errorOf(response, '小C暂时无法回答，请稍后重试。')
          return
        }
        const body = await response.json()
        const pendingIndex = messages.value.findIndex(item => item.id === pendingMessage.id)
        if (pendingIndex >= 0) {
          messages.value.splice(pendingIndex, 1, ...body.messages)
        } else if (currentConversationId.value === conversationId) {
          messages.value.push(...body.messages)
        }
        npcStarted.value = Boolean(body.conversation.npcStarted)
        conversations.value = [body.conversation, ...conversations.value.filter(item => item.id !== body.conversation.id)]
        const latestAssistantMessage = [...body.messages].reverse().find((message: ChatMessage) => message.role === 'assistant')
        if (shouldAutoFollowLatest.value && latestAssistantMessage) {
          await scrollToAnswerStart(latestAssistantMessage.id)
        }
      } catch {
        chatError.value = '网络请求失败，提问已保留，请稍后重试。'
      } finally {
        asking.value = false
      }
    }

    /**
     * 页面初次打开时加载资料归档，不会触发任何模型调用。
     */
    onMounted(async () => {
      await loadConversations()
      if (conversations.value.length) {
        await selectConversation(conversations.value[0])
      } else {
        startNewChat()
      }
      await refreshDocuments()
    })

    return {
      archiveMessage,
      asking,
      chatError,
      closeChatError,
      closeDeleteModal,
      chooseFile,
      conversations,
      confirmDelete,
      composerHint,
      composerPlaceholder,
      currentConversationId,
      currentConversationTitle,
      deleteConversation,
      documentStatusText,
      documents,
      file,
      messageList,
      messages,
      npcStarted,
      pendingDelete,
      question,
      refreshDocuments,
      handleMessageListScroll,
      reindexDocument,
      registerMessageElement,
      removeDocument,
      send,
      selectConversation,
      scrollToMessage,
      startNewChat,
      title,
      questionPreview,
      activePromptId,
      conversationNavigatorPinned,
      conversationNavigatorExpanded,
      conversationNavigator,
      navigatorPreview,
      navigatorAnchorTop,
      keepConversationNavigatorOpen,
      previewNavigatorAtDot,
      scheduleConversationNavigatorClose,
      releaseConversationNavigator,
      selectPromptFromNavigator,
      updateActivePrompt,
      upload,
      uploading,
      deleting,
      userMessages
    }
  },
  template: `
    <div class="app-shell">
      <aside class="left-sidebar">
        <a class="brand" href="#">
          <span class="brand-mark">C</span>
          <span><strong>小C · NPC</strong><small>KNOWLEDGE ASSISTANT</small></span>
        </a>
        <button class="new-chat" @click="startNewChat">＋ 新对话</button>
        <section class="conversation-history">
          <p class="side-title">历史会话</p>
          <div v-for="conversation in conversations" :key="conversation.id" class="history-row" :class="{ active: conversation.id === currentConversationId }">
            <button class="history-item" @click="selectConversation(conversation)"><span>◎</span>{{ conversation.title }}</button>
            <button class="history-delete" title="删除会话" @click="deleteConversation(conversation)">×</button>
          </div>
        </section>
        <div class="sidebar-footer"><span class="status-dot" :class="{ active: npcStarted }"></span>{{ npcStarted ? 'DeepSeek 增强模式已启用' : '本地资料模式运行中' }}</div>
      </aside>

      <main class="chat-main">
        <header class="chat-header"><div><strong>{{ currentConversationTitle }}</strong><small>小C · {{ npcStarted ? 'DeepSeek 增强回答' : '本地资料模式' }}</small></div><span>{{ npcStarted ? 'DeepSeek 已启用' : '本地资料模式' }}</span></header>
        <section ref="messageList" class="message-list" @click="releaseConversationNavigator" @scroll="handleMessageListScroll">
          <article v-for="message in messages" :key="message.id" :ref="element => registerMessageElement(message.id, element)" class="message" :class="message.role">
            <div class="avatar">{{ message.role === 'assistant' ? 'C' : message.role === 'user' ? '我' : '!' }}</div>
            <div class="message-body"><p>{{ message.content }}</p>
              <details v-for="citation in message.citations" :key="citation.documentId + citation.chunkNo" class="citation"><summary>{{ citation.documentTitle }} · 切片 {{ citation.chunkNo }}</summary><p>{{ citation.excerpt }}</p></details>
            </div>
          </article>
          <article v-if="asking" class="message assistant"><div class="avatar">C</div><div class="message-body typing">小C 正在阅读资料并思考…</div></article>
        </section>
        <nav v-if="userMessages.length" ref="conversationNavigator" class="conversation-navigator" :class="{ expanded: conversationNavigatorExpanded, pinned: conversationNavigatorPinned }" aria-label="会话提问导航" @click.stop @mouseleave="scheduleConversationNavigatorClose">
          <div class="navigator-dots" aria-label="提问位置">
            <button v-for="message in userMessages" :key="'dot-' + message.id" class="navigator-dot" :class="{ active: message.id === activePromptId }" :title="questionPreview(message.content)" @mouseenter="previewNavigatorAtDot($event)" @click="selectPromptFromNavigator(message.id, $event)"></button>
          </div>
          <div v-if="conversationNavigatorExpanded" ref="navigatorPreview" class="navigator-preview" :style="{ top: navigatorAnchorTop + 'px' }" aria-label="本次会话的提问列表" @mouseenter="keepConversationNavigatorOpen" @mouseleave="scheduleConversationNavigatorClose">
            <button v-for="message in userMessages" :key="'preview-' + message.id" class="navigator-preview-item" :class="{ active: message.id === activePromptId }" @click="selectPromptFromNavigator(message.id)">{{ questionPreview(message.content) }}</button>
          </div>
        </nav>
        <div class="composer"><textarea v-model="question" @keydown.enter.exact.prevent="send" :placeholder="composerPlaceholder"></textarea><button :disabled="asking || !question.trim()" @click="send">发送 ↑</button><small>{{ composerHint }}</small></div>
      </main>

      <aside class="archive-sidebar">
        <header><div><p class="side-title">资料归档</p><strong>{{ documents.length }} 份资料</strong></div><button class="icon-button" @click="refreshDocuments">↻</button></header>
        <section class="upload-box"><label><span>资料标题（可选）</span><input v-model="title" placeholder="给资料取个名称"></label><label class="file-input"><input type="file" accept=".md,.markdown,.txt,.pdf,.docx" @change="chooseFile"><b>{{ file ? file.name : '＋ 选择资料文件' }}</b><small>支持 Markdown、TXT、PDF、Word</small></label><button class="upload-button" :disabled="!file || uploading" @click="upload">{{ uploading ? '正在归档…' : '上传并解析' }}</button><p v-if="archiveMessage" class="archive-message">{{ archiveMessage }}</p></section>
        <ul class="archive-list"><li v-for="doc in documents" :key="doc.id"><span class="file-badge">{{ doc.fileType.toUpperCase() }}</span><div><strong>{{ doc.title }}</strong><small>原始文件：{{ doc.originalFilename }}</small><em :class="doc.status.toLowerCase()">{{ documentStatusText(doc.status) }}</em><small v-if="doc.failureReason" class="failure">{{ doc.failureReason }}</small></div><div class="archive-actions"><button class="reindex-button" title="重新生成 BGE-M3 向量" @click="reindexDocument(doc.id)">↻</button><button class="delete-button" title="删除资料" @click="removeDocument(doc.id)">×</button></div></li><li v-if="!documents.length" class="empty-archive">还没有资料，先上传一份文件吧。</li></ul>
      </aside>
      <div v-if="chatError" class="error-modal-backdrop" @click.self="closeChatError">
        <section class="error-modal" role="alertdialog" aria-modal="true" aria-labelledby="error-modal-title">
          <span class="error-modal-icon">!</span>
          <h2 id="error-modal-title">操作未完成</h2>
          <p>{{ chatError }}</p>
          <button @click="closeChatError">我知道了</button>
        </section>
      </div>
      <div v-if="pendingDelete" class="delete-modal-backdrop" @click.self="closeDeleteModal">
        <section class="delete-modal" role="alertdialog" aria-modal="true" aria-labelledby="delete-modal-title">
          <header class="delete-modal-heading">
            <div><p class="delete-modal-eyebrow">永久删除</p><h2 id="delete-modal-title">删除{{ pendingDelete.kind === 'conversation' ? '会话' : '资料' }}？</h2></div>
            <button class="delete-modal-close" :disabled="deleting" aria-label="关闭删除确认弹窗" title="保留并关闭" @click="closeDeleteModal">×</button>
          </header>
          <p class="delete-modal-name">{{ pendingDelete.title }}</p>
          <p class="delete-modal-copy">{{ pendingDelete.kind === 'conversation' ? '会话中的全部问答记录将被删除，无法恢复。' : '原始文件、文本切片及 Qdrant 向量索引都会被删除，无法恢复。' }}</p>
          <div class="delete-modal-actions">
            <button class="delete-modal-cancel" :disabled="deleting" @click="closeDeleteModal">保留</button>
            <button class="delete-modal-confirm" :disabled="deleting" @click="confirmDelete">{{ deleting ? '正在删除…' : '确认删除' }}</button>
          </div>
        </section>
      </div>
    </div>
  `
}

createApp(App).mount('#app')
