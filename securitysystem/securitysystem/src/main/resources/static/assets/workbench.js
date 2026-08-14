async function ensureMe() {
  await initCsrf()
  const me = await loadMe()
  if (!me) {
    location.href = "/auth"
    return null
  }
  const mePill = document.getElementById("me-pill")
  if (mePill) {
    const spanEl = mePill.querySelector("span")
    if (spanEl) {
      spanEl.textContent = me.emailOrUsername + " · " + me.role
    }
  }
  const dropdownUserName = document.getElementById("dropdown-user-name")
  const dropdownUserRole = document.getElementById("dropdown-user-role")
  if (dropdownUserName) {
    dropdownUserName.textContent = me.fullName || me.emailOrUsername || "-"
  }
  if (dropdownUserRole) {
    dropdownUserRole.textContent = me.role || "-"
  }
  const dropdownFullname = document.getElementById("dropdown-fullname")
  const dropdownPersonno = document.getElementById("dropdown-personno")
  const dropdownAirline = document.getElementById("dropdown-airline")
  const dropdownPosition = document.getElementById("dropdown-position")
  const dropdownDept = document.getElementById("dropdown-dept")
  if (dropdownFullname) dropdownFullname.textContent = me.fullName || "-"
  if (dropdownPersonno) dropdownPersonno.textContent = me.personNo || me.emailOrUsername || "-"
  if (dropdownAirline) dropdownAirline.textContent = me.airline || "-"
  if (dropdownPosition) dropdownPosition.textContent = me.positionTitle || "-"
  if (dropdownDept) dropdownDept.textContent = me.department || "-"
  if (me.role === "admin") {
    const adminLink = document.getElementById("admin-link")
    if (adminLink) {
      adminLink.style.display = "inline"
    }
    const adminDataLink = document.getElementById("admin-data-link")
    if (adminDataLink) {
      adminDataLink.style.display = "inline"
    }
    const ql = document.getElementById("admin-qar-link")
    if (ql) ql.style.display = "inline"
    const adminMenu = document.getElementById("admin-menu")
    if (adminMenu) {
      adminMenu.style.display = "block"
    }
  } else {
    const uc = document.getElementById("upload-card")
    if (uc) uc.style.display = "none"
    const grid = document.getElementById("grid")
    if (grid) grid.style.gridTemplateColumns = "1fr"
  }
  return me
}

const uploadPolicyState = {
  template: "personal",
  me: null,
  options: {
    departments: [],
    personCategories: ["飞行机组", "运行控制", "安全监察", "机务维护", "通用人员"],
    dutyDomains: ["飞行运行", "运行控制", "安全监管", "维护保障", "综合管理"],
    fleetGroups: []
  }
}

function normalizePolicyValue(v) {
  return (v || "").trim()
}

function buildPolicyClause(key, value) {
  const safe = normalizePolicyValue(value)
  return safe ? `${key}:${safe}` : ""
}

function uniqueClauses(clauses) {
  const out = []
  for (const clause of clauses) {
    if (!clause || out.includes(clause)) continue
    out.push(clause)
  }
  return out
}

function uniqueValues(values) {
  const out = []
  for (const value of values || []) {
    const safe = normalizePolicyValue(value)
    if (!safe || out.includes(safe)) continue
    out.push(safe)
  }
  return out
}

function buildAnyOf(key, values) {
  const clauses = uniqueValues(values).map(value => buildPolicyClause(key, value)).filter(Boolean)
  if (!clauses.length) return ""
  if (clauses.length === 1) return clauses[0]
  return "(" + clauses.join(" OR ") + ")"
}

function buildAll(clauses) {
  return uniqueClauses(clauses).filter(Boolean).join(" AND ")
}

function buildAny(clauses) {
  return uniqueClauses(clauses).filter(Boolean).join(" OR ")
}

function getCheckedPolicyValues(group) {
  return Array.from(document.querySelectorAll(`input[data-policy-group="${group}"]:checked`)).map(el => el.value)
}

function setCheckedPolicyValues(group, values) {
  const wanted = new Set(uniqueValues(values))
  document.querySelectorAll(`input[data-policy-group="${group}"]`).forEach(el => {
    el.checked = wanted.has(el.value)
  })
}

function renderPolicyOptionList(containerId, group, values) {
  const box = document.getElementById(containerId)
  if (!box) return
  box.innerHTML = ""
  const items = uniqueValues(values)
  if (!items.length) {
    const empty = document.createElement("div")
    empty.className = "policy-option-empty"
    empty.textContent = "暂无可选项"
    box.appendChild(empty)
    return
  }
  for (const value of items) {
    const label = document.createElement("label")
    label.className = "policy-option-chip"
    const input = document.createElement("input")
    input.type = "checkbox"
    input.value = value
    input.dataset.policyGroup = group
    input.addEventListener("change", updatePolicyPreview)
    const span = document.createElement("span")
    span.textContent = value
    label.appendChild(input)
    label.appendChild(span)
    box.appendChild(label)
  }
}

async function loadPolicyOptions(me) {
  let persons = []
  try {
    persons = await apiFetch("/api/admin/persons", { method: "GET" })
  } catch (e) {
    persons = []
  }
  const departments = uniqueValues([...(persons || []).map(p => p.department), me && me.department])
  const personCategories = uniqueValues([...(persons || []).map(p => p.personCategory), ...(uploadPolicyState.options.personCategories || []), me && me.personCategory])
  const dutyDomains = uniqueValues([...(persons || []).map(p => p.dutyDomain), ...(uploadPolicyState.options.dutyDomains || []), me && me.dutyDomain])
  const fleetGroups = uniqueValues([...(persons || []).map(p => p.fleetGroup), me && me.fleetGroup])
  uploadPolicyState.options = { departments, personCategories, dutyDomains, fleetGroups }
}

function renderPolicyOptions() {
  renderPolicyOptionList("policy-departments", "department", uploadPolicyState.options.departments)
  renderPolicyOptionList("policy-person-categories", "personCategory", uploadPolicyState.options.personCategories)
  renderPolicyOptionList("policy-duty-domains", "dutyDomain", uploadPolicyState.options.dutyDomains)
  renderPolicyOptionList("policy-fleet-groups", "fleetGroup", uploadPolicyState.options.fleetGroups)
}

function applyTemplateDefaults() {
  const me = uploadPolicyState.me || {}
  if (uploadPolicyState.template === "department" && !getCheckedPolicyValues("department").length && me.department) {
    setCheckedPolicyValues("department", [me.department])
  }
  if (uploadPolicyState.template === "fleet" && !getCheckedPolicyValues("fleetGroup").length && me.fleetGroup) {
    setCheckedPolicyValues("fleetGroup", [me.fleetGroup])
  }
  if (uploadPolicyState.template === "safety") {
    if (!getCheckedPolicyValues("dutyDomain").length) setCheckedPolicyValues("dutyDomain", ["安全监管"])
    if (!getCheckedPolicyValues("personCategory").length) setCheckedPolicyValues("personCategory", ["安全监察"])
  }
}

function updatePolicyTemplateHint() {
  const hint = document.getElementById("policy-template-hint")
  if (!hint) return
  const tips = {
    personal: "个人专属模板会绑定指定工号，适合个人飞行记录或个人专属文件。",
    department: "部门共享支持多选部门，可叠加人员分类、职责域、密级条件。",
    fleet: "机队共享支持多选机队，可叠加人员分类和密级条件。",
    safety: "安全审查支持多选职责域和人员分类，适合监管、审查和复核场景。",
    admin: "仅管理员模板只允许管理员访问。"
  }
  hint.textContent = tips[uploadPolicyState.template] || ""
}

function togglePolicyGroups() {
  const visibleMap = {
    personal: [],
    department: ["policy-group-department", "policy-group-person-category", "policy-group-duty-domain"],
    fleet: ["policy-group-fleet", "policy-group-person-category"],
    safety: ["policy-group-person-category", "policy-group-duty-domain"],
    admin: []
  }
  const visible = new Set(visibleMap[uploadPolicyState.template] || [])
  document.querySelectorAll(".policy-group").forEach(el => {
    el.classList.toggle("is-hidden", !visible.has(el.id))
  })
}

function buildInteractivePolicy() {
  const targetPersonNo = normalizePolicyValue(document.getElementById("target-person-no")?.value)
  const departments = getCheckedPolicyValues("department")
  const personCategories = getCheckedPolicyValues("personCategory")
  const dutyDomains = getCheckedPolicyValues("dutyDomain")
  const fleetGroups = getCheckedPolicyValues("fleetGroup")
  const clearanceLevel = normalizePolicyValue(document.getElementById("policy-clearance-level")?.value)
  const adminBypass = "role:admin"

  if (uploadPolicyState.template === "admin") {
    return adminBypass
  }

  if (uploadPolicyState.template === "personal") {
    return uniqueClauses([buildPolicyClause("personNo", targetPersonNo), adminBypass]).join(" OR ")
  }

  if (uploadPolicyState.template === "department") {
    const scope = buildAll([
      buildAnyOf("department", departments),
      buildAnyOf("personCategory", personCategories),
      buildAnyOf("dutyDomain", dutyDomains),
      buildPolicyClause("clearanceLevel", clearanceLevel)
    ])
    return buildAny([scope, adminBypass])
  }

  if (uploadPolicyState.template === "fleet") {
    const scope = buildAll([
      buildAnyOf("fleetGroup", fleetGroups),
      buildAnyOf("personCategory", personCategories),
      buildPolicyClause("clearanceLevel", clearanceLevel)
    ])
    return buildAny([scope, adminBypass])
  }

  if (uploadPolicyState.template === "safety") {
    const scope = buildAll([
      buildAnyOf("dutyDomain", dutyDomains),
      buildAnyOf("personCategory", personCategories),
      buildPolicyClause("clearanceLevel", clearanceLevel || "L2")
    ])
    return buildAny([scope, adminBypass])
  }

  return buildAny([buildPolicyClause("personNo", targetPersonNo), adminBypass])
}

function updatePolicyPreview() {
  const preview = document.getElementById("policy-preview")
  const value = buildInteractivePolicy()
  if (preview) preview.value = value
  updatePolicyNaturalLanguage(value)
  updatePolicyTreePreview(value)
  return value
}

function updatePolicyNaturalLanguage(policy) {
  const box = document.getElementById("policy-natural-language")
  if (!box) return
  const templateText = {
    personal: "当前是个人专属策略，系统会将文件访问限制给指定工号，并保留管理员兜底访问。",
    department: "当前是部门共享策略，系统会将你选择的部门、人员分类、职责域和密级组合成访问条件。",
    fleet: "当前是机队共享策略，系统会按机队范围和人员分类控制访问。",
    safety: "当前是安全审查策略，系统会将安全监管相关职责域和人员分类组合成审查范围。",
    admin: "当前是仅管理员策略，只有管理员可以访问。"
  }
  box.textContent = templateText[uploadPolicyState.template] || "系统将按所选范围控制访问。"
}

function updatePolicyTreePreview(policy) {
  const box = document.getElementById("policy-tree-preview")
  if (!box) return
  if (!policy) {
    box.textContent = "ROOT"
    return
  }
  box.textContent = renderPolicyTree(parsePolicyTree(policy)) || "ROOT"
}

function parsePolicyTree(policy) {
  const tokens = tokenizePolicy(policy)
  let index = 0
  function peek(type) {
    return index < tokens.length && tokens[index].type === type
  }
  function match(type) {
    if (peek(type)) {
      index++
      return true
    }
    return false
  }
  function expect(type) {
    if (!peek(type)) throw new Error("invalid_policy_expression")
    return tokens[index++]
  }
  function collapse(type, left, right) {
    const children = []
    const push = node => {
      if (node && node.type === type) children.push(...node.children)
      else if (node) children.push(node)
    }
    push(left)
    push(right)
    return { type, children }
  }
  function parseExpression() {
    let value = parseTerm()
    while (match("OR")) value = collapse("OR", value, parseTerm())
    return value
  }
  function parseTerm() {
    let value = parseFactor()
    while (true) {
      if (match("AND")) {
        value = collapse("AND", value, parseFactor())
        continue
      }
      if (peek("ATOM") || peek("LPAREN")) {
        value = collapse("AND", value, parseFactor())
        continue
      }
      break
    }
    return value
  }
  function parseFactor() {
    if (match("LPAREN")) {
      const inner = parseExpression()
      expect("RPAREN")
      return inner
    }
    return { type: "LEAF", value: expect("ATOM").value }
  }
  return parseExpression()
}

function tokenizePolicy(policy) {
  const tokens = []
  let atom = ""
  const flush = () => {
    const raw = atom.trim()
    atom = ""
    if (!raw) return
    const upper = raw.toUpperCase()
    if (upper === "AND") tokens.push({ type: "AND", value: raw })
    else if (upper === "OR") tokens.push({ type: "OR", value: raw })
    else tokens.push({ type: "ATOM", value: raw })
  }
  for (const ch of policy || "") {
    if (/\s|,|;/.test(ch)) {
      flush()
    } else if (ch === "(") {
      flush()
      tokens.push({ type: "LPAREN", value: ch })
    } else if (ch === ")") {
      flush()
      tokens.push({ type: "RPAREN", value: ch })
    } else {
      atom += ch
    }
  }
  flush()
  return tokens
}

function renderPolicyTree(node, indent = "") {
  if (!node) return ""
  if (node.type === "LEAF") return `${indent}LEAF ${node.value}`
  const lines = [`${indent}${node.type}`]
  for (const child of node.children || []) {
    lines.push(renderPolicyTree(child, indent + "  "))
  }
  return lines.join("\n")
}

function renderChipList(containerId, items) {
  const box = document.getElementById(containerId)
  if (!box) return
  box.innerHTML = ""
  const values = items && items.length ? items : ["暂无"]
  for (const item of values) {
    const span = document.createElement("span")
    span.className = "chip" + (item === "暂无" ? " muted" : "")
    span.textContent = item
    box.appendChild(span)
  }
}

function setPolicyTemplate(template) {
  uploadPolicyState.template = template || "personal"
  document.querySelectorAll(".policy-template-btn").forEach(btn => {
    btn.classList.toggle("primary", btn.dataset.template === uploadPolicyState.template)
  })
  const targetInput = document.getElementById("target-person-no")
  if (targetInput) {
    const personal = uploadPolicyState.template === "personal"
    targetInput.disabled = !personal
    targetInput.placeholder = personal ? "例如：20260001 或 user1" : "当前模板不要求指定工号"
  }
  togglePolicyGroups()
  applyTemplateDefaults()
  updatePolicyTemplateHint()
  updatePolicyPreview()
}

async function bindPolicyBuilder(me) {
  uploadPolicyState.me = me || null
  await loadPolicyOptions(me)
  renderPolicyOptions()
  document.querySelectorAll(".policy-template-btn").forEach(btn => {
    btn.addEventListener("click", () => setPolicyTemplate(btn.dataset.template))
  })
  ;["target-person-no", "policy-clearance-level", "policy-data-type"].forEach(id => {
    const el = document.getElementById(id)
    if (!el) return
    el.addEventListener("input", updatePolicyPreview)
    el.addEventListener("change", updatePolicyPreview)
  })
  setPolicyTemplate(uploadPolicyState.template)
}

async function checkTransportCrypto() {
  const statusEl = document.getElementById("crypto-status")
  if (statusEl) {
    statusEl.textContent = "检查中..."
    statusEl.className = "badge"
  }
  
  try {
    await TransportCrypto.ensureSession()
    if (!window.crypto || !window.crypto.subtle) {
      if (statusEl) {
        statusEl.textContent = "✓ 明文传输模式"
        statusEl.className = "badge ok"
      }
    } else {
      if (statusEl) {
        statusEl.textContent = "✓ TLS传输加密已建立"
        statusEl.className = "badge ok"
      }
    }
    return true
  } catch (e) {
    const msg = (e && e.message) || ""
    if (statusEl) {
      if (msg === "transport_handshake_timeout") {
        statusEl.textContent = "✗ 握手超时"
      } else if (msg.includes("未登录") || msg.includes("会话")) {
        statusEl.textContent = "✗ 会话失效"
      } else {
        statusEl.textContent = "✗ TLS传输加密不可用"
      }
      statusEl.className = "badge danger"
    }
    return false
  }
}

async function refreshList() {
  const rows = await TransportCrypto.fetch("/api/files", { method: "GET" })
  const tbody = document.querySelector("#tbl tbody")
  tbody.innerHTML = ""
  document.getElementById("empty").style.display = rows.length ? "none" : "block"
  for (const r of rows) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td><td></td><td class='actions'></td>"
    tr.children[0].textContent = r.originalName
    tr.children[1].textContent = fmtBytes(r.sizeBytes)
    tr.children[2].textContent = (r.createdAt || "").replace("T", " ").replace("Z", "")
    
    const downloadBtn = document.createElement("button")
    downloadBtn.className = "btn"
    downloadBtn.textContent = "下载"
    downloadBtn.onclick = () => onDownload(r.id)
    tr.children[3].appendChild(downloadBtn)
    
    tbody.appendChild(tr)
  }
}

async function refreshStats() {
  try {
    const stats = await TransportCrypto.fetch("/api/files/stats", { method: "GET" })
    const totalUploadsEl = document.getElementById("total-uploads")
    const availableDataEl = document.getElementById("available-data")
    if (totalUploadsEl) {
      totalUploadsEl.textContent = stats.totalUploads || 0
    }
    if (availableDataEl) {
      availableDataEl.textContent = stats.availableData || 0
    }
  } catch (e) {
    console.error("Failed to refresh stats:", e)
  }
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const base64 = (reader.result || "").toString().split(",")[1] || ""
      resolve(base64)
    }
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

function base64ToBlob(base64, contentType) {
  const bin = atob(base64 || "")
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return new Blob([bytes], { type: contentType || "application/octet-stream" })
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = filename || "download.bin"
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

async function onUpload() {
  const fileInput = document.getElementById("file")
  const targetPersonNo = (document.getElementById("target-person-no")?.value || "").trim()
  const btn = document.getElementById("btn-upload")
  const out = document.getElementById("upload-result")
  
  if (!fileInput.files || !fileInput.files.length) {
    showToast("缺少文件", "请选择一个文件", "danger")
    return
  }
  
  const me = await loadMe()
  if (!me || me.role !== "admin") {
    showToast("权限不足", "只有管理员可以上传文件，当前角色: " + (me ? me.role : "未登录"), "danger")
    return
  }
  if (uploadPolicyState.template === "personal" && !targetPersonNo) {
    showToast("缺少归属工号", "请填写要分配下载权限的工号", "danger")
    return
  }
  if (uploadPolicyState.template === "department" && !getCheckedPolicyValues("department").length) {
    showToast("缺少部门范围", "请选择至少一个部门", "danger")
    return
  }
  if (uploadPolicyState.template === "fleet" && !getCheckedPolicyValues("fleetGroup").length) {
    showToast("缺少机队范围", "请选择至少一个机队", "danger")
    return
  }
  const policy = updatePolicyPreview()
  if (!policy) {
    showToast("策略为空", "请先选择访问策略模板", "danger")
    return
  }
  
  await initCsrf()
  const ready = await checkTransportCrypto()
  if (!ready) {
    showToast("传输异常", "TLS传输加密未建立，请刷新后重试", "danger")
    return
  }
  
  btn.disabled = true
  out.textContent = "准备上传..."
  
  try {
    const file = fileInput.files[0]
    out.textContent = "正在读取文件..."
    const fileBase64 = await fileToBase64(file)
    out.textContent = "正在上传..."
    const uploadData = {
      encryptedData: fileBase64,
      wrappedKey: "",
      originalName: file.name,
      contentType: file.type || "application/octet-stream",
      sizeBytes: file.size,
      policy: policy,
      personNo: uploadPolicyState.template === "personal" ? targetPersonNo : ""
    }
    
    const resp = await TransportCrypto.fetch("/api/files/encrypted", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(uploadData)
    })
    
    localStorage.setItem("qar_last_upload_id", resp.id)
    showToast("上传成功", "已加密保存：" + resp.id, "success")
    out.innerHTML = "记录ID：<b></b> · 策略：<span></span> · <a class='link' href='/feedback?fileId=" + encodeURIComponent(resp.id) + "&subject=" + encodeURIComponent("关于记录 " + resp.id + " 的问题") + "'>就此记录提交反馈</a>"
    out.querySelector("b").textContent = resp.id
    out.querySelector("span").textContent = resp.policy || "-"
    fileInput.value = ""
    const targetInput = document.getElementById("target-person-no")
    if (targetInput) targetInput.value = ""
    await refreshList()
    await refreshStats()
  } catch (e) {
    showToast("上传失败", e.message, "danger")
    out.textContent = ""
  } finally {
    btn.disabled = false
  }
}

async function onDownload(fileId) {
  try {
    await checkTransportCrypto()
    showToast("下载中", "正在获取数据...", "info")
    const resp = await TransportCrypto.fetch(`/api/files/${fileId}/payload`, { method: "GET" })
    const blob = base64ToBlob(resp.dataBase64, resp.contentType)
    downloadBlob(blob, resp.originalName || "download.bin")
    showToast("下载完成", "文件已保存", "success")
  } catch (e) {
    showToast("下载失败", e.message, "danger")
  }
}

async function onLogout() {
  try {
    await apiFetch("/api/auth/logout", { method: "POST" })
    location.href = "/auth"
  } catch (e) {
    showToast("退出失败", e.message, "danger")
  }
}

async function main() {
  const me = await ensureMe()
  if (!me) return
  const mePill = document.getElementById("me-pill")
  const dropdownMenu = mePill ? mePill.querySelector(".dropdown-menu") : null
  
  if (mePill && dropdownMenu) {
    mePill.addEventListener("click", function(e) {
      e.stopPropagation()
      dropdownMenu.classList.toggle("show")
    })
    
    document.addEventListener("click", function(e) {
      if (!mePill.contains(e.target)) {
        dropdownMenu.classList.remove("show")
      }
    })
  }
  
  const btnLogout = document.getElementById("btn-logout")
  if (btnLogout) {
    btnLogout.addEventListener("click", onLogout)
  }
  
  await checkTransportCrypto()
  await refreshStats()
  
  if (me.role === "admin") {
    await bindPolicyBuilder(me)
    const btnUpload = document.getElementById("btn-upload")
    if (btnUpload) {
      btnUpload.addEventListener("click", onUpload)
    }
  }
  await refreshList()
}

main()
