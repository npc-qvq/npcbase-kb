import { computed, createApp, nextTick, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import DOMPurify from 'dompurify'
import {
  ArrowDown,
  Check,
  ChevronDown,
  Copy,
  Eraser,
  FileText,
  Filter,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  RefreshCw,
  RotateCcw,
  Search,
  Send,
  Square,
  Sun
} from '@lucide/vue'
import 'highlight.js/styles/github.css'
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
  createdAt?: string
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

/** 后端返回的当前浏览器访问权限与公开体验状态。 */
type AccessStatus = {
  unlocked: boolean
  demoConversationId: string | null
  remainingMessages: number
  messageLimit: number
  publicProvider: string
}

/** Markdown 代码块渲染器，为每段代码提供语言标识和独立复制按钮。 */
const markdownRenderer = new marked.Renderer()
markdownRenderer.code = ({ text, lang }) => {
  const requestedLanguage = (lang || '').trim().split(/\s+/)[0]
  const language = requestedLanguage && hljs.getLanguage(requestedLanguage) ? requestedLanguage : ''
  const highlighted = language
    ? hljs.highlight(text, { language }).value
    : hljs.highlightAuto(text).value
  const encodedCode = encodeURIComponent(text)
  return `<div class="code-block"><div class="code-toolbar"><span>${language || 'text'}</span><button type="button" data-code-copy="${encodedCode}" aria-label="复制代码">复制代码</button></div><pre><code class="hljs${language ? ` language-${language}` : ''}">${highlighted}</code></pre></div>`
}
marked.setOptions({ gfm: true, breaks: true, renderer: markdownRenderer })

const App = {
  components: {
    ArrowDown,
    Check,
    ChevronDown,
    Copy,
    Eraser,
    FileText,
    Filter,
    Moon,
    PanelLeftClose,
    PanelLeftOpen,
    PanelRightClose,
    PanelRightOpen,
    RefreshCw,
    RotateCcw,
    Search,
    Send,
    Square,
    Sun
  },
  setup() {
    /** 当前打开会话中小C是否已收到启动口令。 */
    const npcStarted = ref(false)

    /** 发送给后端的小C角色提示词，仅在启用大模型增强回答时使用。 */
    const assistantPrompt = '你叫小C，是 NPC 的个人知识库助手。请使用中文，优先根据右侧归档资料回答；资料不足时如实说明。'

    /** 后端可用的大模型提供商列表。 */
    type ProviderInfo = { name: string; displayName: string; model: string; enabled: boolean; configured: boolean }

    /** 从后端加载的对话模型提供商列表。 */
    const providers = ref<ProviderInfo[]>([])

    /** 当前激活的对话模型提供商名称。 */
    const activeProviderName = ref('')

    /** 当前激活的对话模型提供商显示名称。 */
    const activeProviderDisplayName = computed(() => {
      const provider = providers.value.find(p => p.name === activeProviderName.value)
      return provider?.displayName || activeProviderName.value || '大模型'
    })

    /** 是否正在切换提供商，切换期间禁用模型选择器。 */
    const switchingProvider = ref(false)

    /** 自定义模型选择菜单是否展开。 */
    const providerMenuOpen = ref(false)

    /** 当前浏览器是否已经通过唯一访问密钥解锁。 */
    const accessUnlocked = ref(false)

    /** 服务器配置的唯一公开测试会话主键。 */
    const demoConversationId = ref('')

    /** 当前匿名访客剩余的测试会话发送次数。 */
    const remainingMessages = ref(0)

    /** 单个匿名访客允许的测试会话消息上限。 */
    const publicMessageLimit = ref(5)

    /** 密钥输入弹窗是否正在显示。 */
    const accessModalOpen = ref(false)

    /** 密钥弹窗中尚未提交的访问密钥。 */
    const accessKey = ref('')

    /** 密钥验证或权限接口失败时展示的错误。 */
    const accessError = ref('')

    /** 密钥验证请求执行期间禁用重复提交。 */
    const unlockingAccess = ref(false)

    /** 密钥输入框元素，用于打开弹窗后自动聚焦。 */
    const accessKeyInput = ref<HTMLInputElement | null>(null)

    /** 中间输入框中尚未发送的内容，可输入启动口令或正常问题。 */
    const question = ref('')

    /** 小C正在等待模型回答时禁用重复发送。 */
    const asking = ref(false)

    /** 对话区需要展示的网络或业务错误。 */
    const chatError = ref('')

    function readPreference(key: string) {
      try {
        return localStorage.getItem(key)
      } catch {
        return null
      }
    }

    function writePreference(key: string, value: string) {
      try {
        localStorage.setItem(key, value)
      } catch {
        // 浏览器禁用本地存储时保持内存状态即可。
      }
    }

    /** 对话区内不打断阅读的轻提示。 */
    const chatNotice = ref('')

    /** 输入框元素，用于重试或继续追问后恢复键盘焦点。 */
    const composerInput = ref<HTMLTextAreaElement | null>(null)

    /** 纯前端会话标题搜索词。 */
    const conversationSearch = ref('')

    /** 左右侧栏折叠状态和主题均只保存在当前浏览器。 */
    const leftSidebarCollapsed = ref(readPreference('kb-left-sidebar') === 'collapsed')
    const rightSidebarCollapsed = ref(readPreference('kb-right-sidebar') === 'collapsed')
    const darkMode = ref(readPreference('kb-theme') === 'dark')

    /** 首次请求期间展示骨架和空状态。 */
    const loadingConversations = ref(true)
    const loadingMessages = ref(false)
    const loadingDocuments = ref(true)

    /** 最近完成整条复制的消息主键。 */
    const copiedMessageId = ref('')

    let activeChatController: AbortController | null = null
    let copiedMessageTimer: number | undefined

    /** 等待用户确认删除的会话或资料。 */
    const pendingDelete = ref<PendingDelete | null>(null)

    /** 删除请求执行期间禁用弹窗按钮，避免重复提交。 */
    const deleting = ref(false)

    /** 等待用户确认清空消息的当前会话；为空时不显示清空确认弹窗。 */
    const pendingClearConversation = ref<Conversation | null>(null)

    /** 清空请求执行期间禁用弹窗和标题栏按钮，避免重复提交。 */
    const clearingConversation = ref(false)

    /** 当前正在编辑名称的历史会话；为空时不显示重命名弹窗。 */
    const editingConversation = ref<Conversation | null>(null)

    /** 重命名弹窗中尚未提交的会话名称。 */
    const conversationTitleDraft = ref('')

    /** 会话重命名请求执行期间禁用弹窗按钮，避免重复提交。 */
    const renamingConversation = ref(false)

    /** 重命名校验或接口失败时在弹窗内展示的错误。 */
    const renameConversationError = ref('')

    /** 重命名输入框元素，用于打开弹窗后自动聚焦并选中原名称。 */
    const renameInput = ref<HTMLInputElement | null>(null)

    /** 对话区展示当前选中会话的消息序列。 */
    const messages = ref<ChatMessage[]>([])

    /** 后端数据库保存的历史会话，用于左栏展示与切换。 */
    const conversations = ref<Conversation[]>([])

    /** 当前正在查看的会话主键。 */
    const currentConversationId = ref('')

    /** 当前会话标题，用于中间栏头部标识正在进行的会话。 */
    const currentConversationTitle = computed(() => conversations.value.find(item => item.id === currentConversationId.value)?.title || '新对话')

    /** 当前选择的是否为服务器指定的公开测试会话。 */
    const isPublicDemoConversation = computed(() => Boolean(currentConversationId.value)
      && currentConversationId.value === demoConversationId.value)

    /** 未解锁访客是否已经用完测试会话的公开发送次数。 */
    const publicQuotaExhausted = computed(() => !accessUnlocked.value
      && isPublicDemoConversation.value
      && remainingMessages.value <= 0)

    /** 当前浏览器是否允许在所选会话中发送消息。 */
    const canSendMessage = computed(() => accessUnlocked.value
      || (isPublicDemoConversation.value && remainingMessages.value > 0))

    /** 页面实际展示的模型模式。 */
    const effectiveNpcStarted = computed(() => npcStarted.value)

    /** 当前权限和会话对应的输入框占位提示。 */
    const composerPlaceholder = computed(() => {
      if (!accessUnlocked.value && !isPublicDemoConversation.value) {
        return '该会话为只读内容，输入访问密钥后才能提问…'
      }
      if (publicQuotaExhausted.value) {
        return '公开体验次数已用完，输入访问密钥后继续…'
      }
      if (!accessUnlocked.value) {
        return npcStarted.value
          ? `智谱 GLM 已启用，还可提问 ${remainingMessages.value} 次…`
          : `公开测试还可提问 ${remainingMessages.value} 次；输入“小c启动”可启用大模型回答…`
      }
      return npcStarted.value
        ? `${activeProviderDisplayName.value} 已启用；继续提问，或输入"小c关闭"切回资料检索模式…`
        : '直接提问，或输入"小c启动"启用大模型增强回答…'
    })

    /** 当前权限和模型模式对应的输入框辅助说明。 */
    const composerHint = computed(() => {
      if (!accessUnlocked.value && !isPublicDemoConversation.value) {
        return '公开模式可查询该会话，但不能在这里发送消息'
      }
      if (!accessUnlocked.value) {
        return publicQuotaExhausted.value
          ? '体验次数已用完 · 输入密钥可解锁全部操作'
          : npcStarted.value
            ? `公开体验 · 智谱 GLM 已启用 · 剩余 ${remainingMessages.value}/${publicMessageLimit.value} 次 · 输入“小c关闭”可回到资料模式`
            : `公开体验 · 剩余 ${remainingMessages.value}/${publicMessageLimit.value} 次 · 输入“小c启动”可启用大模型回答`
      }
      return npcStarted.value
        ? `Enter 发送 · 输入"小c关闭"可停止调用 ${activeProviderDisplayName.value}`
        : 'Enter 发送 · 输入"小c启动"后才调用大模型'
    })

    /** 右侧归档区从后端读取的文档列表。 */
    const documents = ref<DocumentItem[]>([])

    /** 纯前端资料标题、文件名搜索词与处理状态筛选。 */
    const documentSearch = ref('')
    const documentStatusFilter = ref('ALL')
    const documentFilterMenuOpen = ref(false)
    const documentFilterOptions = [
      { value: 'ALL', label: '全部状态' },
      { value: 'INDEXED', label: '可提问' },
      { value: 'PROCESSING', label: '处理中' },
      { value: 'FAILED', label: '失败' }
    ]
    const documentStatusFilterLabel = computed(() => documentFilterOptions.find(option => option.value === documentStatusFilter.value)?.label || '全部状态')

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

    /** 程序化平滑滚动结束后释放目标高亮的计时器。 */
    let navigatorScrollUnlockTimer: number | undefined

    /** 点击导航后正在滚动前往的用户消息主键；非空时禁止滚动监听覆盖高亮。 */
    let navigatorScrollTargetId = ''

    /** 当前阅读位置对应的最近一条用户提问，用于在导航列表中高亮。 */
    const activePromptId = ref('')

    /** 已渲染消息元素的引用，用于点击缩略块后准确跳转到对应消息。 */
    const messageElements = new Map<string, HTMLElement>()

    /** 当前会话中的用户提问列表；右侧导航只展示提问，不展示小C完整回答。 */
    const userMessages = computed(() => messages.value.filter(message => message.role === 'user'))

    /** 会话和资料搜索仅过滤已加载数据，不改变服务端内容。 */
    const filteredConversations = computed(() => {
      const keyword = conversationSearch.value.trim().toLocaleLowerCase()
      if (!keyword) {
        return conversations.value
      }
      return conversations.value.filter(item => item.title.toLocaleLowerCase().includes(keyword))
    })

    const filteredDocuments = computed(() => {
      const keyword = documentSearch.value.trim().toLocaleLowerCase()
      return documents.value.filter(item => {
        const matchesKeyword = !keyword
          || item.title.toLocaleLowerCase().includes(keyword)
          || item.originalFilename.toLocaleLowerCase().includes(keyword)
        const matchesStatus = documentStatusFilter.value === 'ALL'
          || (documentStatusFilter.value === 'PROCESSING' && ['UPLOADED', 'PARSING', 'INDEXING'].includes(item.status))
          || item.status === documentStatusFilter.value
        return matchesKeyword && matchesStatus
      })
    })

    const showBackToLatest = computed(() => messages.value.length > 0 && !shouldAutoFollowLatest.value)

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

    /** 安全渲染小C回答中的 Markdown，脚本和危险属性会被清理。 */
    function renderMarkdown(content: string) {
      const html = marked.parse(content, { async: false }) as string
      return DOMPurify.sanitize(html)
    }

    /** 将文本写入剪贴板，并兼容不支持 Clipboard API 的浏览器。 */
    async function copyText(value: string) {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value)
        return
      }
      const helper = document.createElement('textarea')
      helper.value = value
      helper.style.position = 'fixed'
      helper.style.opacity = '0'
      document.body.appendChild(helper)
      helper.select()
      document.execCommand('copy')
      helper.remove()
    }

    /** 处理 Markdown 代码块中的独立复制按钮。 */
    async function handleMarkdownClick(event: MouseEvent) {
      const target = event.target
      if (!(target instanceof Element)) {
        return
      }
      const button = target.closest<HTMLButtonElement>('[data-code-copy]')
      if (!button?.dataset.codeCopy) {
        return
      }
      try {
        await copyText(decodeURIComponent(button.dataset.codeCopy))
        button.textContent = '已复制'
        window.setTimeout(() => { button.textContent = '复制代码' }, 1200)
      } catch {
        chatNotice.value = '复制失败，请手动选择代码'
      }
    }

    /** 复制一整条问答内容。 */
    async function copyMessage(message: ChatMessage) {
      try {
        await copyText(message.content)
        copiedMessageId.value = message.id
        if (copiedMessageTimer !== undefined) {
          window.clearTimeout(copiedMessageTimer)
        }
        copiedMessageTimer = window.setTimeout(() => { copiedMessageId.value = '' }, 1400)
      } catch {
        chatNotice.value = '复制失败，请手动选择内容'
      }
    }

    /** 在消息下方显示简短时间，避免为无时间字段的旧数据制造假时间。 */
    function formatMessageTime(value?: string) {
      if (!value) {
        return ''
      }
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) {
        return ''
      }
      return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(date)
    }

    function toggleLeftSidebar() {
      leftSidebarCollapsed.value = !leftSidebarCollapsed.value
      writePreference('kb-left-sidebar', leftSidebarCollapsed.value ? 'collapsed' : 'expanded')
    }

    function toggleRightSidebar() {
      rightSidebarCollapsed.value = !rightSidebarCollapsed.value
      writePreference('kb-right-sidebar', rightSidebarCollapsed.value ? 'collapsed' : 'expanded')
    }

    function toggleTheme() {
      darkMode.value = !darkMode.value
      writePreference('kb-theme', darkMode.value ? 'dark' : 'light')
    }

    async function focusComposer() {
      await nextTick()
      composerInput.value?.focus()
    }

    /** 输入内容较短时自然增高，长内容达到上限后在输入框内部滚动。 */
    function resizeComposer() {
      const input = composerInput.value
      if (!input) {
        return
      }
      input.style.height = 'auto'
      const nextHeight = Math.min(Math.max(input.scrollHeight, 58), 180)
      input.style.height = `${nextHeight}px`
      input.style.overflowY = input.scrollHeight > 180 ? 'auto' : 'hidden'
    }

    function conversationDraftKey(conversationId: string) {
      return `kb-draft-${conversationId}`
    }

    function restoreConversationDraft() {
      if (!currentConversationId.value) {
        question.value = ''
        return
      }
      question.value = readPreference(conversationDraftKey(currentConversationId.value)) || ''
      void nextTick(resizeComposer)
    }

    watch(question, () => {
      if (currentConversationId.value) {
        writePreference(conversationDraftKey(currentConversationId.value), question.value)
      }
      void nextTick(resizeComposer)
    })

    watch(currentConversationId, restoreConversationDraft)

    /** 从回答回到输入框继续追问，不向服务端写入额外状态。 */
    async function continueFromMessage() {
      chatNotice.value = '可以继续补充条件或追问细节'
      await focusComposer()
    }

    /** 找到该回答前最近的用户问题并通过现有问答接口再次提交。 */
    async function retryAssistantMessage(messageId: string) {
      const answerIndex = messages.value.findIndex(item => item.id === messageId)
      if (answerIndex < 0 || asking.value) {
        return
      }
      const source = messages.value.slice(0, answerIndex).reverse().find(item => item.role === 'user')
      if (!source) {
        return
      }
      await send(source.content)
    }

    /** 只终止当前浏览器等待，不依赖新增后端接口。 */
    function stopAnswer() {
      activeChatController?.abort()
    }

    /**
     * 将后端访问状态同步到页面响应式状态。
     *
     * @param status 后端返回的访问状态
     * @returns 无返回值
     */
    function applyAccessStatus(status: AccessStatus) {
      accessUnlocked.value = Boolean(status.unlocked)
      demoConversationId.value = status.demoConversationId || ''
      remainingMessages.value = Math.max(0, Number(status.remainingMessages) || 0)
      publicMessageLimit.value = Math.max(0, Number(status.messageLimit) || 0)
    }

    /**
     * 打开访问密钥弹窗，并在渲染完成后聚焦输入框。
     *
     * @param message 可选的权限或额度提示
     * @returns 输入框完成聚焦后的 Promise
     */
    async function openAccessModal(message = '') {
      accessModalOpen.value = true
      accessError.value = message
      await nextTick()
      accessKeyInput.value?.focus()
      accessKeyInput.value?.select()
    }

    /**
     * 关闭访问密钥弹窗并清空明文密钥。
     *
     * @returns 无返回值
     */
    function closeAccessModal() {
      if (unlockingAccess.value) {
        return
      }
      accessModalOpen.value = false
      accessKey.value = ''
      accessError.value = ''
    }

    /**
     * 从后端加载当前浏览器的解锁状态和公开体验剩余次数。
     *
     * @returns 状态加载结束后的 Promise
     */
    async function loadAccessStatus() {
      const response = await request('/api/access/status')
      if (!response.ok) {
        return
      }
      applyAccessStatus(await response.json() as AccessStatus)
    }

    /**
     * 提交唯一访问密钥，成功后刷新模型提供商和全部操作权限。
     *
     * @returns 密钥验证结束后的 Promise
     */
    async function unlockAccess() {
      if (!accessKey.value.trim() || unlockingAccess.value) {
        return
      }
      unlockingAccess.value = true
      accessError.value = ''
      try {
        const response = await request('/api/access/unlock', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ key: accessKey.value })
        })
        if (!response.ok) {
          accessError.value = await errorOf(response, '访问密钥验证失败')
          return
        }
        applyAccessStatus(await response.json() as AccessStatus)
        accessModalOpen.value = false
        accessKey.value = ''
        await loadProviders()
      } catch {
        accessError.value = '网络连接失败，请确认后端服务正在运行'
      } finally {
        unlockingAccess.value = false
      }
    }

    /**
     * 删除当前浏览器的访问凭证并恢复公开只读模式。
     *
     * @returns 重新锁定结束后的 Promise
     */
    async function lockAccess() {
      const response = await request('/api/access/unlock', { method: 'DELETE' })
      if (!response.ok) {
        chatError.value = await errorOf(response, '重新锁定失败')
        return
      }
      accessUnlocked.value = false
      providerMenuOpen.value = false
      await loadAccessStatus()
      await loadProviders()
    }

    /**
     * 调用后端接口并自动携带 HttpOnly 访问凭证，权限不足时打开密钥弹窗。
     *
     * @param path 后端接口路径
     * @param options fetch 请求配置
     * @returns 后端原始响应对象
     */
    async function request(path: string, options: RequestInit = {}) {
      const response = await fetch(path, { credentials: 'same-origin', ...options })
      if (response.status === 403) {
        try {
          const body = await response.clone().json()
          if (['KEY_REQUIRED', 'PUBLIC_QUOTA_EXHAUSTED', 'PUBLIC_RATE_LIMITED'].includes(body.code)) {
            await openAccessModal(body.message || '请输入访问密钥后继续')
          }
        } catch {
          // 非 JSON 权限响应交由具体业务使用默认错误提示处理。
        }
      }
      return response
    }

    /**
     * 从错误响应中提取后端消息，权限错误已由密钥弹窗处理时返回空文本。
     *
     * @param response 后端错误响应
     * @param fallback 默认错误文本
     * @returns 可直接展示的错误信息
     */
    async function errorOf(response: Response, fallback: string) {
      try {
        const body = await response.json()
        if (['KEY_REQUIRED', 'PUBLIC_QUOTA_EXHAUSTED', 'PUBLIC_RATE_LIMITED'].includes(body.code)) {
          return ''
        }
        return body.message || fallback
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
     * 从后端加载对话模型提供商列表和当前激活状态。
     */
    async function loadProviders() {
      const response = await request('/api/npc/providers')
      if (response.ok) {
        const body = await response.json()
        activeProviderName.value = body.activeProvider || ''
        providers.value = body.providers || []
      }
    }

    /**
     * 切换激活的对话模型提供商。
     *
     * @param name 目标提供商名称
     */
    async function switchProvider(name: string) {
      if (!accessUnlocked.value) {
        await openAccessModal('切换模型需要先输入访问密钥')
        return
      }
      if (switchingProvider.value || name === activeProviderName.value) {
        return
      }
      switchingProvider.value = true
      try {
        const response = await request(`/api/npc/provider?name=${encodeURIComponent(name)}`, { method: 'POST' })
        if (response.ok) {
          const body = await response.json()
          activeProviderName.value = body.activeProvider || name
        } else {
          chatError.value = await errorOf(response, '切换模型失败')
        }
      } catch {
        chatError.value = '网络请求失败，模型切换未完成。'
      } finally {
        switchingProvider.value = false
      }
    }

    /**
     * 展开或收起模型选择菜单；切换请求执行期间保持菜单关闭。
     */
    function toggleProviderMenu() {
      if (!accessUnlocked.value) {
        void openAccessModal('切换模型需要先输入访问密钥')
        return
      }
      if (switchingProvider.value) {
        return
      }
      providerMenuOpen.value = !providerMenuOpen.value
    }

    /**
     * 收起模型选择菜单，不改变当前激活的模型。
     */
    function closeProviderMenu() {
      providerMenuOpen.value = false
    }

    function toggleDocumentFilterMenu() {
      documentFilterMenuOpen.value = !documentFilterMenuOpen.value
    }

    function closeDocumentFilterMenu() {
      documentFilterMenuOpen.value = false
    }

    function selectDocumentFilter(value: string) {
      documentStatusFilter.value = value
      closeDocumentFilterMenu()
    }

    function closeFloatingMenus() {
      closeProviderMenu()
      closeDocumentFilterMenu()
    }

    /**
     * 从自定义圆角菜单选择可用模型，并复用原有后端切换接口。
     *
     * @param provider 用户选择的模型提供商
     */
    async function selectProvider(provider: ProviderInfo) {
      if (!provider.enabled || !provider.configured) {
        return
      }
      closeProviderMenu()
      await switchProvider(provider.name)
    }

    /**
     * 刷新右侧已归档资料，便于上传、删除和重建索引后同步状态。
     */
    async function refreshDocuments() {
      loadingDocuments.value = true
      try {
        const response = await request('/api/documents')
        if (response.ok) {
          documents.value = (await response.json()).content || []
        }
      } finally {
        loadingDocuments.value = false
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
      if (!accessUnlocked.value) {
        await openAccessModal('上传资料需要先输入访问密钥')
        return
      }
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
      if (!accessUnlocked.value) {
        await openAccessModal('删除资料需要先输入访问密钥')
        return
      }
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
      if (!accessUnlocked.value) {
        await openAccessModal('重新索引资料需要先输入访问密钥')
        return
      }
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
      loadingConversations.value = true
      try {
        const response = await request('/api/conversations')
        if (response.ok) {
          conversations.value = await response.json()
        }
      } finally {
        loadingConversations.value = false
      }
    }

    /**
     * 创建数据库会话，并将其切换为当前对话。
     */
    async function startNewChat() {
      if (!accessUnlocked.value) {
        await openAccessModal('创建会话需要先输入访问密钥')
        return
      }
      const response = await request('/api/conversations', { method: 'POST' })
      if (!response.ok) {
        chatError.value = await errorOf(response, '加载会话失败')
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
      loadingMessages.value = true
      chatNotice.value = ''
      try {
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
      } finally {
        loadingMessages.value = false
      }
    }

    /**
     * 打开当前会话的清空确认弹窗，未解锁时先请求访问密钥。
     *
     * @returns 权限检查和弹窗状态更新完成后的 Promise
     */
    async function openClearConversationModal() {
      if (!accessUnlocked.value) {
        await openAccessModal('清空会话需要先输入访问密钥')
        return
      }
      const conversation = conversations.value.find(item => item.id === currentConversationId.value)
      if (!conversation || asking.value) {
        return
      }
      pendingClearConversation.value = conversation
    }

    /**
     * 关闭清空确认弹窗，不修改会话或消息数据。
     */
    function closeClearConversationModal() {
      if (!clearingConversation.value) {
        pendingClearConversation.value = null
      }
    }

    /**
     * 删除当前会话的全部服务端消息，默认启用智谱 GLM，并同步清空页面消息和本地输入草稿。
     *
     * @returns 清空请求结束后的 Promise
     */
    async function confirmClearConversation() {
      if (!accessUnlocked.value) {
        await openAccessModal('清空会话需要先输入访问密钥')
        return
      }
      const target = pendingClearConversation.value
      if (!target || clearingConversation.value || asking.value) {
        return
      }
      clearingConversation.value = true
      try {
        // 服务端物理删除当前会话的全部消息，刷新页面后也保持为空。
        const response = await request(`/api/conversations/${target.id}/messages`, { method: 'DELETE' })
        if (!response.ok) {
          chatError.value = await errorOf(response, '清空会话失败')
          return
        }
        const updatedConversation: Conversation = await response.json()
        conversations.value = conversations.value.map(item => item.id === updatedConversation.id ? updatedConversation : item)
        // 清空接口会把服务端默认提供商切换为智谱 GLM，重新读取后同步标题栏模型名称。
        await loadProviders()
        if (currentConversationId.value === target.id) {
          messages.value = []
          npcStarted.value = Boolean(updatedConversation.npcStarted)
          question.value = ''
          chatNotice.value = '本次会话已清空，智谱 GLM 已启用'
          releaseConversationNavigator()
          await focusComposer()
        }
        pendingClearConversation.value = null
      } catch {
        chatError.value = '网络连接失败，请确认后端服务正在运行'
      } finally {
        clearingConversation.value = false
      }
    }

    /**
     * 删除指定会话及其数据库消息记录。
     *
     * @param conversation 待删除的历史会话
     */
    async function deleteConversation(conversation: Conversation) {
      if (!accessUnlocked.value) {
        await openAccessModal('删除会话需要先输入访问密钥')
        return
      }
      pendingDelete.value = { kind: 'conversation', id: conversation.id, title: conversation.title }
    }

    /**
     * 打开会话名称编辑弹窗，并选中原名称方便直接覆盖输入。
     *

     * @param conversation 待重命名的历史会话
     * @returns 输入框完成聚焦后的 Promise
     */
    async function openRenameConversation(conversation: Conversation) {
      if (!accessUnlocked.value) {
        await openAccessModal('编辑会话名称需要先输入访问密钥')
        return
      }
      editingConversation.value = conversation
      conversationTitleDraft.value = conversation.title
      renameConversationError.value = ''
      await nextTick()
      renameInput.value?.focus()
      renameInput.value?.select()
    }

    /**
     * 关闭会话名称编辑弹窗并清空临时输入。
     *
     * @returns 无返回值
     */
    function closeRenameConversationModal() {
      if (renamingConversation.value) {
        return
      }
      editingConversation.value = null
      conversationTitleDraft.value = ''
      renameConversationError.value = ''
    }

    /**
     * 校验并提交新会话名称，成功后同步历史列表及当前会话标题。
     *
     * @returns 重命名请求结束后的 Promise
     */
    async function confirmRenameConversation() {
      if (!accessUnlocked.value) {
        await openAccessModal('编辑会话名称需要先输入访问密钥')
        return
      }
      const target = editingConversation.value
      const nextTitle = conversationTitleDraft.value.trim()
      if (!target || renamingConversation.value) {
        return
      }
      if (!nextTitle) {
        renameConversationError.value = '请输入会话名称'
        return
      }
      if (nextTitle.length > 60) {
        renameConversationError.value = '会话名称不能超过 60 个字符'
        return
      }

      renamingConversation.value = true
      renameConversationError.value = ''
      try {
        const response = await request(`/api/conversations/${target.id}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title: nextTitle })
        })
        if (!response.ok) {
          renameConversationError.value = await errorOf(response, '修改会话名称失败')
          return
        }
        const updatedConversation: Conversation = await response.json()
        conversations.value = conversations.value.map(item => item.id === updatedConversation.id ? updatedConversation : item)
        editingConversation.value = null
        conversationTitleDraft.value = ''
      } catch {
        renameConversationError.value = '网络连接失败，请确认后端服务正在运行'
      } finally {
        renamingConversation.value = false
      }
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
      if (!accessUnlocked.value) {
        await openAccessModal('删除操作需要先输入访问密钥')
        return
      }
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
      if (navigatorScrollTargetId) {
        activePromptId.value = navigatorScrollTargetId
        scheduleNavigatorScrollUnlock()
        return
      }
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
      if (navigatorScrollTargetId) {
        activePromptId.value = navigatorScrollTargetId
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
     * 延迟释放程序化滚动锁；连续滚动事件会刷新计时，确保平滑滚动彻底结束。
     *
     * @param delay 距离最后一次滚动事件的等待毫秒数
     */
    function scheduleNavigatorScrollUnlock(delay = 180) {
      if (navigatorScrollUnlockTimer !== undefined) {
        window.clearTimeout(navigatorScrollUnlockTimer)
      }
      navigatorScrollUnlockTimer = window.setTimeout(() => {
        navigatorScrollUnlockTimer = undefined
        navigatorScrollTargetId = ''
      }, delay)
    }

    /** 清除尚未完成的程序化定位状态。 */
    function clearNavigatorScrollLock() {
      if (navigatorScrollUnlockTimer !== undefined) {
        window.clearTimeout(navigatorScrollUnlockTimer)
        navigatorScrollUnlockTimer = undefined
      }
      navigatorScrollTargetId = ''
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
      clearNavigatorScrollLock()
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
      navigatorScrollTargetId = messageId
      activePromptId.value = messageId
      const targetTop = target.offsetTop - messageList.value.offsetTop - 20
      messageList.value.scrollTo({ top: Math.max(targetTop, 0), behavior: 'smooth' })
      scheduleNavigatorScrollUnlock(600)
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
     * 提交启动口令或问题；默认使用本地资料模式，启动小C后才请求大模型。
     */
    async function send(contentOverride?: string | Event) {
      if (!canSendMessage.value) {
        const message = publicQuotaExhausted.value
          ? '公开体验次数已用完，请输入访问密钥继续'
          : '该会话为只读内容，输入访问密钥后才能提问'
        await openAccessModal(message)
        return
      }
      const content = typeof contentOverride === 'string'
        ? contentOverride.trim()
        : question.value.trim()
      if (!content || asking.value) {
        return
      }
      chatError.value = ''
      chatNotice.value = ''
      if (typeof contentOverride !== 'string') {
        question.value = ''
      }
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
      const controller = new AbortController()
      activeChatController = controller
      // “正在思考”消息刚插入后再次滚动，确保它完整显示在底部输入框上方。
      await scrollToLatestMessage()
      try {
        // 后端保存用户输入，未启动时使用本地资料模式，启动后调用大模型。
        const response = await request(`/api/conversations/${conversationId}/messages`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          signal: controller.signal,
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
        if (!accessUnlocked.value) {
          // 公开回答成功后从服务器刷新剩余次数，避免前端自行计数被绕过。
          await loadAccessStatus()
        }
        const latestAssistantMessage = [...body.messages].reverse().find((message: ChatMessage) => message.role === 'assistant')
        if (shouldAutoFollowLatest.value && latestAssistantMessage) {
          await scrollToAnswerStart(latestAssistantMessage.id)
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') {
          chatNotice.value = '已停止等待；服务端若已完成处理，重新进入会话即可看到结果'
        } else {
          chatError.value = '网络请求失败，提问已保留，请稍后重试。'
        }
      } finally {
        if (activeChatController === controller) {
          activeChatController = null
        }
        asking.value = false
      }
    }

    /**
     * 页面初次打开时加载资料归档，不会触发任何模型调用。
     */
    onMounted(async () => {
      if (window.innerWidth <= 1180 && readPreference('kb-right-sidebar') === null) {
        rightSidebarCollapsed.value = true
      }
      if (window.innerWidth <= 760 && readPreference('kb-left-sidebar') === null) {
        leftSidebarCollapsed.value = true
      }
      await loadAccessStatus()
      await loadProviders()
      await loadConversations()
      const preferredConversation = !accessUnlocked.value
        ? conversations.value.find(item => item.id === demoConversationId.value) || conversations.value[0]
        : conversations.value[0]
      if (preferredConversation) {
        await selectConversation(preferredConversation)
      } else if (accessUnlocked.value) {
        await startNewChat()
      }
      await refreshDocuments()
    })

    return {
      activeProviderDisplayName,
      activeProviderName,
      archiveMessage,
      asking,
      chatError,
      chatNotice,
      clearingConversation,
      closeChatError,
      closeClearConversationModal,
      closeDeleteModal,
      chooseFile,
      conversations,
      conversationSearch,
      filteredConversations,
      confirmDelete,
      confirmClearConversation,
      composerHint,
      composerPlaceholder,
      currentConversationId,
      currentConversationTitle,
      deleteConversation,
      documentStatusText,
      documents,
      documentSearch,
      documentStatusFilter,
      documentStatusFilterLabel,
      documentFilterMenuOpen,
      documentFilterOptions,
      filteredDocuments,
      file,
      loadProviders,
      closeProviderMenu,
      closeDocumentFilterMenu,
      closeFloatingMenus,
      messageList,
      messages,
      loadingConversations,
      loadingDocuments,
      loadingMessages,
      npcStarted,
      pendingDelete,
      pendingClearConversation,
      providers,
      providerMenuOpen,
      question,
      composerInput,
      resizeComposer,
      refreshDocuments,
      handleMessageListScroll,
      reindexDocument,
      registerMessageElement,
      removeDocument,
      send,
      stopAnswer,
      retryAssistantMessage,
      continueFromMessage,
      copyMessage,
      copiedMessageId,
      renderMarkdown,
      handleMarkdownClick,
      formatMessageTime,
      selectConversation,
      selectProvider,
      scrollToMessage,
      startNewChat,
      switchingProvider,
      switchProvider,
      title,
      toggleProviderMenu,
      toggleDocumentFilterMenu,
      selectDocumentFilter,
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
      showBackToLatest,
      scrollToLatestMessage,
      leftSidebarCollapsed,
      rightSidebarCollapsed,
      darkMode,
      toggleLeftSidebar,
      toggleRightSidebar,
      toggleTheme,
      deleting,
      editingConversation,
      conversationTitleDraft,
      renamingConversation,
      renameConversationError,
      renameInput,
      openRenameConversation,
      openClearConversationModal,
      closeRenameConversationModal,
      confirmRenameConversation,
      accessUnlocked,
      demoConversationId,
      remainingMessages,
      publicMessageLimit,
      accessModalOpen,
      accessKey,
      accessError,
      unlockingAccess,
      accessKeyInput,
      isPublicDemoConversation,
      publicQuotaExhausted,
      canSendMessage,
      effectiveNpcStarted,
      openAccessModal,
      closeAccessModal,
      unlockAccess,
      lockAccess,
      userMessages
    }
  },
  template: `
    <div class="app-shell" :class="{ 'left-collapsed': leftSidebarCollapsed, 'right-collapsed': rightSidebarCollapsed, 'theme-dark': darkMode }" @click="closeFloatingMenus">
      <aside class="left-sidebar" :class="{ collapsed: leftSidebarCollapsed }">
        <a class="brand" href="#">
          <span class="brand-mark">C</span>
          <span><strong>小C · NPC</strong><small>KNOWLEDGE ASSISTANT</small></span>
        </a>
        <button class="new-chat" :class="{ locked: !accessUnlocked }" @click="startNewChat">{{ accessUnlocked ? '＋ 新对话' : '🔒 新对话' }}</button>
        <section class="access-card" :class="{ unlocked: accessUnlocked }">
          <div><span class="access-card-dot"></span><strong>{{ accessUnlocked ? '全部操作已解锁' : '公开体验模式' }}</strong></div>
          <small>{{ accessUnlocked ? '会话、资料和模型均可管理' : '仅测试会话可提问，其他内容只读' }}</small>
          <button v-if="accessUnlocked" type="button" @click="lockAccess">重新锁定</button>
          <button v-else type="button" @click="openAccessModal()">输入访问密钥</button>
        </section>
        <section class="conversation-history">
          <div class="section-heading"><p class="side-title">历史会话</p><small>{{ filteredConversations.length }}</small></div>
          <label class="search-field conversation-search">
            <Search :size="14" aria-hidden="true" />
            <input v-model="conversationSearch" type="search" placeholder="搜索会话" aria-label="搜索会话">
          </label>
          <div v-if="loadingConversations" class="sidebar-skeleton" aria-label="正在加载会话"><span></span><span></span><span></span></div>
          <div v-for="conversation in filteredConversations" v-else :key="conversation.id" class="history-row" :class="{ active: conversation.id === currentConversationId, demo: conversation.id === demoConversationId }">
            <button class="history-item" @click="selectConversation(conversation)">
              <span class="history-icon">◎</span><span class="history-title">{{ conversation.title }}</span><small v-if="conversation.id === demoConversationId" class="history-demo-badge">测试</small>
            </button>
            <div class="history-actions">
              <button class="history-edit" title="编辑会话名称" aria-label="编辑会话名称" @click.stop="openRenameConversation(conversation)">✎</button>
              <button class="history-delete" title="删除会话" aria-label="删除会话" @click.stop="deleteConversation(conversation)">×</button>
            </div>
          </div>
          <p v-if="!loadingConversations && !filteredConversations.length" class="sidebar-empty">{{ conversationSearch ? '没有匹配的会话' : '暂无历史会话' }}</p>
        </section>
        <div class="sidebar-footer">
          <span class="status-dot" :class="{ active: effectiveNpcStarted }"></span>
          <span class="status-label">{{ effectiveNpcStarted ? activeProviderDisplayName + (accessUnlocked ? ' 增强模式已启用' : ' 公开体验') : '本地资料模式运行中' }}</span>
          <div v-if="providers.length > 1" class="provider-picker" :class="{ open: providerMenuOpen }" @click.stop @keydown.esc="closeProviderMenu">
            <button
              type="button"
              class="provider-trigger"
              :class="{ locked: !accessUnlocked }"
              :disabled="switchingProvider"
              aria-haspopup="listbox"
              :aria-expanded="providerMenuOpen"
              @click="toggleProviderMenu"
            >
              <span class="provider-trigger-label">{{ activeProviderDisplayName }}</span>
              <span class="provider-chevron" aria-hidden="true">{{ accessUnlocked ? '⌄' : '🔒' }}</span>
            </button>
            <div v-if="providerMenuOpen && accessUnlocked" class="provider-menu" role="listbox" aria-label="选择对话模型">
              <button
                v-for="provider in providers"
                :key="provider.name"
                type="button"
                role="option"
                class="provider-option"
                :class="{ active: provider.name === activeProviderName }"
                :aria-selected="provider.name === activeProviderName"
                :disabled="!provider.enabled || !provider.configured"
                @click="selectProvider(provider)"
              >
                <span>{{ provider.displayName }}</span>
                <small v-if="provider.name === activeProviderName">当前</small>
                <small v-else-if="!provider.enabled || !provider.configured">未配置</small>
              </button>
            </div>
          </div>
        </div>
      </aside>

      <main class="chat-main">
        <header class="chat-header">
          <div class="chat-heading">
            <button class="icon-button header-panel-toggle" type="button" :title="leftSidebarCollapsed ? '展开会话栏' : '收起会话栏'" :aria-label="leftSidebarCollapsed ? '展开会话栏' : '收起会话栏'" @click="toggleLeftSidebar"><PanelLeftOpen v-if="leftSidebarCollapsed" :size="17" /><PanelLeftClose v-else :size="17" /></button>
            <div><strong>{{ currentConversationTitle }}</strong><small>小C · {{ effectiveNpcStarted ? activeProviderDisplayName + ' 增强回答' : '本地资料模式' }}</small></div>
          </div>
          <div class="chat-header-actions">
            <span class="chat-access-badge" :class="{ locked: !accessUnlocked }">{{ accessUnlocked ? (effectiveNpcStarted ? activeProviderDisplayName + ' 已启用' : '本地资料模式') : (isPublicDemoConversation ? '公开测试 · 剩余 ' + remainingMessages + ' 次' : '只读会话') }}</span>
            <button class="icon-button clear-conversation-button" type="button" :disabled="!currentConversationId || asking || clearingConversation" :title="accessUnlocked ? '清空本次会话' : '输入密钥后清空会话'" :aria-label="accessUnlocked ? '清空本次会话' : '输入密钥后清空会话'" @click="openClearConversationModal"><Eraser :size="17" /></button>
            <button class="icon-button" type="button" :title="darkMode ? '切换浅色主题' : '切换深色主题'" :aria-label="darkMode ? '切换浅色主题' : '切换深色主题'" @click="toggleTheme"><Sun v-if="darkMode" :size="17" /><Moon v-else :size="17" /></button>
            <button class="icon-button header-panel-toggle" type="button" :title="rightSidebarCollapsed ? '展开资料栏' : '收起资料栏'" :aria-label="rightSidebarCollapsed ? '展开资料栏' : '收起资料栏'" @click="toggleRightSidebar"><PanelRightOpen v-if="rightSidebarCollapsed" :size="17" /><PanelRightClose v-else :size="17" /></button>
          </div>
        </header>
        <section ref="messageList" class="message-list" @click="releaseConversationNavigator" @scroll="handleMessageListScroll">
          <div v-if="loadingMessages" class="message-skeleton" aria-label="正在加载会话消息"><span></span><span></span><span></span></div>
          <div v-else-if="!messages.length" class="empty-chat">
            <span class="empty-chat-icon"><FileText :size="22" /></span>
            <strong>从资料中开始一次提问</strong>
            <p>{{ canSendMessage ? '输入问题后，小C 会优先检索右侧已归档资料。' : '当前会话只读，输入访问密钥后可以继续提问。' }}</p>
          </div>
          <article v-for="message in messages" :key="message.id" :ref="element => registerMessageElement(message.id, element)" class="message" :class="message.role">
            <div class="avatar">{{ message.role === 'assistant' ? 'C' : message.role === 'user' ? '我' : '✦' }}</div>
            <div class="message-column">
              <div class="message-body">
                <p v-if="message.role === 'user'" class="user-content">{{ message.content }}</p>
                <div v-else class="markdown-body" v-html="renderMarkdown(message.content)" @click="handleMarkdownClick"></div>
              <details v-for="citation in message.citations" :key="citation.documentId + citation.chunkNo" class="citation"><summary>{{ citation.documentTitle }} · 切片 {{ citation.chunkNo }}</summary><p>{{ citation.excerpt }}</p></details>
              </div>
              <div class="message-meta">
                <span v-if="formatMessageTime(message.createdAt)">{{ formatMessageTime(message.createdAt) }}</span>
                <button type="button" :title="copiedMessageId === message.id ? '已复制' : '复制消息'" @click.stop="copyMessage(message)"><Copy :size="14" />{{ copiedMessageId === message.id ? '已复制' : '复制' }}</button>
                <button v-if="message.role === 'assistant'" type="button" title="重新提交上一条问题" :disabled="asking" @click.stop="retryAssistantMessage(message.id)"><RotateCcw :size="14" />重试</button>
                <button v-if="message.role === 'assistant'" type="button" title="继续追问" @click.stop="continueFromMessage"><Send :size="14" />追问</button>
              </div>
            </div>
          </article>
          <article v-if="asking" class="message assistant pending-answer"><div class="avatar">C</div><div class="message-body typing"><span></span><span></span><span></span><b>小C 正在阅读资料并组织回答</b></div></article>
        </section>
        <button v-if="showBackToLatest" class="back-to-latest" type="button" @click="scrollToLatestMessage"><ArrowDown :size="16" />回到最新</button>
        <nav v-if="userMessages.length" ref="conversationNavigator" class="conversation-navigator" :class="{ expanded: conversationNavigatorExpanded, pinned: conversationNavigatorPinned }" aria-label="会话提问导航" @click.stop @mouseleave="scheduleConversationNavigatorClose">
          <div class="navigator-dots" aria-label="提问位置">
            <button v-for="message in userMessages" :key="'dot-' + message.id" class="navigator-dot" :class="{ active: message.id === activePromptId }" :title="questionPreview(message.content)" @mouseenter="previewNavigatorAtDot($event)" @click="selectPromptFromNavigator(message.id, $event)"></button>
          </div>
          <div v-if="conversationNavigatorExpanded" ref="navigatorPreview" class="navigator-preview" :style="{ top: navigatorAnchorTop + 'px' }" aria-label="本次会话的提问列表" @mouseenter="keepConversationNavigatorOpen" @mouseleave="scheduleConversationNavigatorClose">
            <button v-for="message in userMessages" :key="'preview-' + message.id" class="navigator-preview-item" :class="{ active: message.id === activePromptId }" @click="selectPromptFromNavigator(message.id)">{{ questionPreview(message.content) }}</button>
          </div>
        </nav>
        <p v-if="chatNotice" class="chat-notice" role="status">{{ chatNotice }}</p>
        <div class="composer" :class="{ locked: !canSendMessage }">
          <textarea ref="composerInput" v-model="question" :disabled="!canSendMessage" @input="resizeComposer" @keydown.enter.exact.prevent="send()" :placeholder="composerPlaceholder" aria-label="输入问题"></textarea>
          <button v-if="!canSendMessage" class="composer-action unlock" type="button" @click="openAccessModal()">输入密钥</button>
          <button v-else-if="asking" class="composer-action stop" type="button" title="停止等待" aria-label="停止等待" @click="stopAnswer"><Square :size="15" />停止</button>
          <button v-else class="composer-action send" type="button" :disabled="!question.trim()" title="发送消息" @click="send()"><Send :size="16" />发送</button>
          <small>{{ composerHint }}</small>
        </div>
      </main>

      <aside class="archive-sidebar" :class="{ collapsed: rightSidebarCollapsed }">
        <header>
          <div><p class="side-title">资料归档</p><strong>{{ documents.length }} 份资料</strong></div>
          <div class="archive-header-actions">
            <button class="icon-button" type="button" :disabled="loadingDocuments" title="刷新资料" aria-label="刷新资料" @click="refreshDocuments"><RefreshCw :size="16" :class="{ spinning: loadingDocuments }" /></button>
          </div>
        </header>
        <div class="archive-content">
        <section class="archive-tools">
          <label class="search-field">
            <Search :size="14" aria-hidden="true" />
            <input v-model="documentSearch" type="search" placeholder="搜索资料" aria-label="搜索资料">
          </label>
          <div class="document-filter" :class="{ open: documentFilterMenuOpen }" @click.stop @keydown.esc="closeDocumentFilterMenu">
            <button type="button" class="document-filter-trigger" aria-haspopup="listbox" :aria-expanded="documentFilterMenuOpen" title="按处理状态筛选" @click="toggleDocumentFilterMenu">
              <Filter :size="14" aria-hidden="true" />
              <span>{{ documentStatusFilterLabel }}</span>
              <ChevronDown :size="14" class="document-filter-chevron" aria-hidden="true" />
            </button>
            <div v-if="documentFilterMenuOpen" class="document-filter-menu" role="listbox" aria-label="按处理状态筛选">
              <button v-for="option in documentFilterOptions" :key="option.value" type="button" role="option" class="document-filter-option" :class="{ active: option.value === documentStatusFilter }" :aria-selected="option.value === documentStatusFilter" @click="selectDocumentFilter(option.value)">
                <span>{{ option.label }}</span>
                <Check v-if="option.value === documentStatusFilter" :size="14" aria-hidden="true" />
              </button>
            </div>
          </div>
        </section>
        <section class="upload-box" :class="{ locked: !accessUnlocked }">
          <div v-if="!accessUnlocked" class="locked-operation">
            <span>🔒</span><strong>资料管理已锁定</strong><small>上传、重新索引和删除资料都需要访问密钥。</small>
            <button type="button" @click="openAccessModal('资料管理需要先输入访问密钥')">输入访问密钥</button>
          </div>
          <template v-else>
            <label><span>资料标题（可选）</span><input v-model="title" placeholder="给资料取个名称"></label>
            <label class="file-input"><input type="file" accept=".md,.markdown,.txt,.pdf,.docx" @change="chooseFile"><b>{{ file ? file.name : '＋ 选择资料文件' }}</b><small>支持 Markdown、TXT、PDF、Word</small></label>
            <button class="upload-button" :disabled="!file || uploading" @click="upload">{{ uploading ? '正在归档…' : '上传并解析' }}</button>
            <p v-if="archiveMessage" class="archive-message">{{ archiveMessage }}</p>
          </template>
        </section>
        <div v-if="loadingDocuments" class="archive-skeleton" aria-label="正在加载资料"><span></span><span></span><span></span></div>
        <ul v-else class="archive-list"><li v-for="doc in filteredDocuments" :key="doc.id"><span class="file-badge">{{ doc.fileType.toUpperCase() }}</span><div><strong>{{ doc.title }}</strong><small>原始文件：{{ doc.originalFilename }}</small><em :class="doc.status.toLowerCase()">{{ documentStatusText(doc.status) }}</em><small v-if="doc.failureReason" class="failure">{{ doc.failureReason }}</small></div><div class="archive-actions" :class="{ locked: !accessUnlocked }"><button class="reindex-button" :title="accessUnlocked ? '重新生成 BGE-M3 向量' : '输入密钥后重新索引'" @click="reindexDocument(doc.id)">{{ accessUnlocked ? '↻' : '🔒' }}</button><button class="delete-button" :title="accessUnlocked ? '删除资料' : '输入密钥后删除'" @click="removeDocument(doc.id)">{{ accessUnlocked ? '×' : '🔒' }}</button></div></li><li v-if="!filteredDocuments.length" class="empty-archive">{{ documents.length ? '没有符合筛选条件的资料。' : '还没有资料，先上传一份文件吧。' }}</li></ul>
        </div>
      </aside>
      <div v-if="accessModalOpen" class="access-modal-backdrop" @click.self="closeAccessModal">
        <section class="access-modal" role="dialog" aria-modal="true" aria-labelledby="access-modal-title" @keydown.esc="closeAccessModal">
          <header class="access-modal-heading">
            <span class="access-modal-icon">🔐</span>
            <div><p>OWNER ACCESS</p><h2 id="access-modal-title">输入访问密钥</h2></div>
            <button type="button" :disabled="unlockingAccess" aria-label="关闭密钥弹窗" @click="closeAccessModal">×</button>
          </header>
          <p class="access-modal-copy">验证成功后将解锁会话管理、资料管理和模型切换，24 小时内无需重复输入。</p>
          <form @submit.prevent="unlockAccess">
            <label><span>唯一访问密钥</span><input ref="accessKeyInput" v-model="accessKey" type="password" maxlength="128" autocomplete="current-password" placeholder="请输入访问密钥" @input="accessError = ''"></label>
            <p v-if="accessError" class="access-modal-error" role="alert">{{ accessError }}</p>
            <p class="access-modal-security">密钥仅发送给后端验证，不会保存在前端代码或浏览器存储中。</p>
            <div class="access-modal-actions">
              <button class="access-modal-cancel" type="button" :disabled="unlockingAccess" @click="closeAccessModal">取消</button>
              <button class="access-modal-confirm" type="submit" :disabled="unlockingAccess || !accessKey.trim()">{{ unlockingAccess ? '正在验证…' : '验证并解锁' }}</button>
            </div>
          </form>
        </section>
      </div>
      <div v-if="chatError" class="error-modal-backdrop" @click.self="closeChatError">
        <section class="error-modal" role="alertdialog" aria-modal="true" aria-labelledby="error-modal-title">
          <span class="error-modal-icon">!</span>
          <h2 id="error-modal-title">网络挂掉了（qvq）</h2>
          <p>{{ chatError }}</p>
          <button @click="closeChatError">我知道了</button>
        </section>
      </div>
      <div v-if="editingConversation" class="rename-modal-backdrop" @click.self="closeRenameConversationModal">
        <section class="rename-modal" role="dialog" aria-modal="true" aria-labelledby="rename-modal-title" @keydown.esc="closeRenameConversationModal">
          <header class="rename-modal-heading">
            <div><p class="rename-modal-eyebrow">整理会话</p><h2 id="rename-modal-title">编辑会话名称</h2></div>
            <button class="rename-modal-close" type="button" :disabled="renamingConversation" aria-label="关闭名称编辑弹窗" @click="closeRenameConversationModal">×</button>
          </header>
          <p class="rename-modal-copy">用一个容易辨认的名称标记这段会话。</p>
          <form @submit.prevent="confirmRenameConversation">
            <label class="rename-modal-field">
              <span>会话名称</span>
              <input ref="renameInput" v-model="conversationTitleDraft" maxlength="60" autocomplete="off" placeholder="请输入会话名称" @input="renameConversationError = ''">
            </label>
            <div class="rename-modal-meta">
              <span v-if="renameConversationError" class="rename-modal-error" role="alert">{{ renameConversationError }}</span>
              <span class="rename-modal-counter">{{ conversationTitleDraft.length }}/60</span>
            </div>
            <div class="rename-modal-actions">
              <button class="rename-modal-cancel" type="button" :disabled="renamingConversation" @click="closeRenameConversationModal">取消</button>
              <button class="rename-modal-confirm" type="submit" :disabled="renamingConversation || !conversationTitleDraft.trim()">{{ renamingConversation ? '正在保存…' : '保存名称' }}</button>
            </div>
          </form>
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
      <div v-if="pendingClearConversation" class="delete-modal-backdrop" @click.self="closeClearConversationModal">
        <section class="delete-modal" role="alertdialog" aria-modal="true" aria-labelledby="clear-modal-title">
          <header class="delete-modal-heading">
            <div><p class="delete-modal-eyebrow">清空记录</p><h2 id="clear-modal-title">清空本次会话？</h2></div>
            <button class="delete-modal-close" :disabled="clearingConversation" aria-label="关闭清空确认弹窗" title="保留并关闭" @click="closeClearConversationModal">×</button>
          </header>
          <p class="delete-modal-name">{{ pendingClearConversation.title }}</p>
          <p class="delete-modal-copy">本次会话中的全部提问和回答将被永久删除，会话名称和知识库资料会保留；清空后将默认启用智谱 GLM。</p>
          <div class="delete-modal-actions">
            <button class="delete-modal-cancel" :disabled="clearingConversation" @click="closeClearConversationModal">保留记录</button>
            <button class="delete-modal-confirm" :disabled="clearingConversation" @click="confirmClearConversation">{{ clearingConversation ? '正在清空…' : '确认清空' }}</button>
          </div>
        </section>
      </div>
    </div>
  `
}

createApp(App).mount('#app')
