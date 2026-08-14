let allPersons = []
let selectedPersonId = null

let personSortField = "createdAt"
let personSortOrder = "desc"

async function ensureAdmin() {
  await initCsrf()
  const me = await loadMe()
  if (!me) {
    location.href = "/auth"
    return null
  }
  if (me.role !== "admin") {
    location.href = "/workbench"
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
  return me
}

async function refreshPersons() {
  try {
    const rows = await apiFetch("/api/admin/labe/persons", { method: "GET" })
    allPersons = rows || []
    applyPersonFiltersAndSort()
    if (!selectedPersonId && allPersons.length) {
      selectedPersonId = allPersons[0].id
    }
    renderSelectedPerson()
  } catch (e) {
    showToast("加载人员数据失败", e.message, "danger")
  }
}

function applyPersonFiltersAndSort() {
  const keyword = (document.getElementById("search-keyword").value || "").toLowerCase()
  const startDate = document.getElementById("filter-date-start").value
  const endDate = document.getElementById("filter-date-end").value

  let filtered = allPersons.filter(p => {
    const matchesKeyword =
      (p.personNo || "").toLowerCase().includes(keyword) ||
      (p.fullName || "").toLowerCase().includes(keyword) ||
      (p.department || "").toLowerCase().includes(keyword) ||
      (p.airline || "").toLowerCase().includes(keyword) ||
      (p.positionTitle || "").toLowerCase().includes(keyword) ||
      (p.personCategory || "").toLowerCase().includes(keyword) ||
      (p.dutyDomain || "").toLowerCase().includes(keyword) ||
      (p.fleetGroup || "").toLowerCase().includes(keyword) ||
      (p.clearanceLevel || "").toLowerCase().includes(keyword) ||
      (p.attributes || []).join(" ").toLowerCase().includes(keyword)

    let matchesDate = true
    if (p.createdAt) {
      const createdDate = p.createdAt.split("T")[0]
      if (startDate && createdDate < startDate) matchesDate = false
      if (endDate && createdDate > endDate) matchesDate = false
    } else if (startDate || endDate) {
      matchesDate = false
    }

    return matchesKeyword && matchesDate
  })

  filtered.sort((a, b) => {
    let valA = a[personSortField] || ""
    let valB = b[personSortField] || ""
    if (typeof valA === "string") valA = valA.toLowerCase()
    if (typeof valB === "string") valB = valB.toLowerCase()
    if (valA < valB) return personSortOrder === "asc" ? -1 : 1
    if (valA > valB) return personSortOrder === "asc" ? 1 : -1
    return 0
  })

  renderPersonTable(filtered)
}

function renderPersonTable(data) {
  const tbody = document.querySelector("#persons tbody")
  tbody.innerHTML = ""
  const emptyMsg = document.getElementById("empty-msg");
  
  if (data.length === 0) {
    emptyMsg.style.display = "block";
    return;
  }
  emptyMsg.style.display = "none";

  for (const r of data) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td class='actions'></td>"
    tr.children[0].textContent = r.personNo
    tr.children[1].textContent = r.fullName
    tr.children[2].textContent = r.airline || "-"
    tr.children[3].textContent = r.positionTitle || "-"
    tr.children[4].textContent = r.department || "-"
    tr.children[5].textContent = r.personCategory || "-"
    tr.children[6].textContent = r.dutyDomain || "-"
    tr.children[7].textContent = r.fleetGroup || "-"
    tr.children[8].textContent = r.clearanceLevel || "-"
    tr.children[9].innerHTML = renderLabeStatus(r)
    tr.children[10].textContent = r.phone || "-"
    tr.children[11].textContent = (r.createdAt || "").replace("T", " ").replace("Z", "").slice(0, 16)

    const btnEdit = document.createElement("button")
    btnEdit.className = "btn"
    btnEdit.textContent = "编辑"
    btnEdit.addEventListener("click", () => onEditPerson(r))

    const btnDel = document.createElement("button")
    btnDel.className = "btn danger"
    btnDel.textContent = "删除"
    btnDel.style.backgroundColor = "#ff4d4f"
    btnDel.style.color = "white"
    btnDel.addEventListener("click", () => onDeletePerson(r))

    const btnIssue = document.createElement("button")
    btnIssue.className = "btn primary"
    btnIssue.textContent = r.secretBundleReady ? "重发钥" : "发钥"
    btnIssue.disabled = !r.accountReady
    btnIssue.addEventListener("click", () => onIssuePersonKey(r))

    const btnAccess = document.createElement("button")
    btnAccess.className = "btn"
    btnAccess.textContent = r.accessEnabled ? "冻结访问" : "恢复访问"
    btnAccess.disabled = !r.accountReady
    btnAccess.addEventListener("click", () => r.accessEnabled ? onFreezePersonAccess(r) : onRestorePersonAccess(r))

    tr.children[12].appendChild(btnEdit)
    tr.children[12].appendChild(btnIssue)
    tr.children[12].appendChild(btnAccess)
    tr.children[12].appendChild(btnDel)
    tr.addEventListener("click", (e) => {
      if (e.target.closest("button")) return
      selectedPersonId = r.id
      renderSelectedPerson()
      applyPersonFiltersAndSort()
    })
    tr.classList.toggle("selected", selectedPersonId === r.id)
    tbody.appendChild(tr)
  }
}

function renderLabeStatus(r) {
  const account = r.accountReady ? "<span class='badge ok'>已建账号</span>" : "<span class='badge warn'>未建账号</span>"
  const access = r.accessEnabled ? "<span class='badge ok'>访问正常</span>" : "<span class='badge danger'>已冻结</span>"
  const version = r.secretBundleVersion ? ` V${r.secretBundleVersion}` : ""
  const bundle = r.secretBundleReady ? `<span class='badge ok'>已发钥${version}</span>` : "<span class='badge'>未发钥</span>"
  return account + " " + access + " " + bundle
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

function renderSelectedPerson() {
  const person = allPersons.find(item => item.id === selectedPersonId) || null
  const summary = document.getElementById("person-labe-summary")
  if (!summary) return
  if (!person) {
    summary.textContent = "请选择一名人员查看L-ABE详情。"
    renderChipList("person-labe-attributes", [])
    renderChipList("person-labe-authorities", [])
    return
  }
  const accountText = person.accountReady ? "已建立账号" : "未建立账号"
  const keyText = person.secretBundleReady ? "属性密钥材料已生成" : "属性密钥材料尚未生成"
  const accessText = person.accessEnabled ? "L-ABE访问已启用" : "L-ABE访问已冻结"
  const versionText = person.secretBundleVersion ? `V${person.secretBundleVersion}` : "-"
  const issuedAtText = person.secretBundleIssuedAt ? person.secretBundleIssuedAt.replace("T", " ").replace("Z", "").slice(0, 19) : "-"
  const issuedReasonText = person.secretBundleIssuedReason || "-"
  const digestText = person.secretBundleAttributeDigest || "-"
  const revokedAtText = person.accessRevokedAt ? person.accessRevokedAt.replace("T", " ").replace("Z", "").slice(0, 19) : "-"
  const revokedReasonText = person.accessRevokedReason || "-"
  summary.replaceChildren()
  const appendLine = (...parts) => {
    const line = document.createElement("div")
    for (const part of parts) {
      if (part instanceof Node) line.appendChild(part)
      else line.appendChild(document.createTextNode(String(part)))
    }
    summary.appendChild(line)
  }
  const name = document.createElement("strong")
  name.textContent = person.fullName || "-"
  appendLine(name, `（${person.personNo || "-"}）`)
  appendLine(`部门：${person.department || "-"}，职责域：${person.dutyDomain || "-"}`)
  appendLine(`人员分类：${person.personCategory || "-"}，机队：${person.fleetGroup || "-"}`)
  appendLine(`账号状态：${accountText}`)
  appendLine(`访问状态：${accessText}`)
  appendLine(`发钥状态：${keyText}`)
  appendLine(`材料版本：${versionText}`)
  appendLine(`最近发钥：${issuedAtText}`)
  appendLine(`最近原因：${issuedReasonText}`)
  appendLine(`最近冻结：${revokedAtText}`)
  appendLine(`冻结原因：${revokedReasonText}`)
  appendLine(`属性摘要：${digestText}`)
  renderChipList("person-labe-attributes", person.attributes || [])
  renderChipList("person-labe-authorities", person.authorities || [])
}

async function onIssuePersonKey(person) {
  if (!person) {
    showToast("未选择人员", "请先选择一名人员", "danger")
    return
  }
  if (!person.accountReady) {
    showToast("无法发钥", "该人员尚未建立账号", "danger")
    return
  }
  try {
    await apiFetch("/api/admin/labe/persons/" + person.id + "/issue", { method: "POST" })
    showToast("发钥完成", `${person.personNo} 已更新L-ABE材料`, "success")
    await refreshPersons()
  } catch (e) {
    showToast("发钥失败", e.message, "danger")
  }
}

async function onFreezePersonAccess(person) {
  if (!person) {
    showToast("未选择人员", "请先选择一名人员", "danger")
    return
  }
  if (!person.accountReady) {
    showToast("无法冻结", "该人员尚未建立账号", "danger")
    return
  }
  const reason = window.prompt("输入冻结原因：", person.accessRevokedReason || "admin_access_frozen")
  if (reason === null) return
  try {
    await apiFetch("/api/admin/labe/persons/" + person.id + "/freeze", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason })
    })
    showToast("已冻结", `${person.personNo} 的L-ABE访问已冻结`, "success")
    await refreshPersons()
  } catch (e) {
    showToast("冻结失败", e.message, "danger")
  }
}

async function onRestorePersonAccess(person) {
  if (!person) {
    showToast("未选择人员", "请先选择一名人员", "danger")
    return
  }
  if (!person.accountReady) {
    showToast("无法恢复", "该人员尚未建立账号", "danger")
    return
  }
  const reason = window.prompt("输入恢复原因：", "admin_access_restored")
  if (reason === null) return
  try {
    await apiFetch("/api/admin/labe/persons/" + person.id + "/restore", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason })
    })
    showToast("已恢复", `${person.personNo} 已重新获得L-ABE访问`, "success")
    await refreshPersons()
  } catch (e) {
    showToast("恢复失败", e.message, "danger")
  }
}

async function onEditPerson(r) {
  const name = window.prompt("姓名：", r.fullName)
  if (name === null) return
  const airline = window.prompt("航司：", r.airline)
  if (airline === null) return
  const pos = window.prompt("职位：", r.positionTitle)
  if (pos === null) return
  const dep = window.prompt("部门：", r.department)
  if (dep === null) return
  const personCategory = window.prompt("人员分类：", r.personCategory || "通用人员")
  if (personCategory === null) return
  const dutyDomain = window.prompt("职责域：", r.dutyDomain || "综合管理")
  if (dutyDomain === null) return
  const fleetGroup = window.prompt("机队：", r.fleetGroup || "通用机队")
  if (fleetGroup === null) return
  const clearanceLevel = window.prompt("密级：", r.clearanceLevel || "L1")
  if (clearanceLevel === null) return
  const phone = window.prompt("电话：", r.phone)
  if (phone === null) return

  try {
    await apiFetch("/api/admin/persons/" + r.id, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        fullName: name,
        airline: airline,
        positionTitle: pos,
        department: dep,
        personCategory: personCategory,
        dutyDomain: dutyDomain,
        fleetGroup: fleetGroup,
        clearanceLevel: clearanceLevel,
        phone: phone
      })
    })
    showToast("已更新", r.personNo, "success")
    await refreshPersons()
  } catch (e) {
    showToast("更新失败", e.message, "danger")
  }
}

async function onDeletePerson(r) {
  if (!confirm("确定要删除 " + r.personNo + " 吗？")) return
  try {
    await apiFetch("/api/admin/persons/" + r.id, { method: "DELETE" })
    showToast("已删除", r.personNo, "success")
    await refreshPersons()
  } catch (e) {
    showToast("删除失败", e.message, "danger")
  }
}

async function onAddPerson() {
  const personNo = window.prompt("工号：")
  if (!personNo) return
  const fullName = window.prompt("姓名：")
  if (!fullName) return
  const idLast4 = window.prompt("身份证后四位：")
  if (!idLast4) return
  const airline = window.prompt("航司：")
  const positionTitle = window.prompt("职位：")
  const department = window.prompt("部门：")
  const personCategory = window.prompt("人员分类：", "通用人员")
  const dutyDomain = window.prompt("职责域：", "综合管理")
  const fleetGroup = window.prompt("机队：", "通用机队")
  const clearanceLevel = window.prompt("密级：", "L1")
  const phone = window.prompt("电话：")

  try {
    await apiFetch("/api/admin/persons", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        personNo, fullName, idLast4, airline, positionTitle, department, personCategory, dutyDomain, fleetGroup, clearanceLevel, phone
      })
    })
    showToast("已添加", personNo, "success")
    await refreshPersons()
  } catch (e) {
    showToast("添加失败", e.message, "danger")
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

function bufToB64(buf) {
  const bytes = new Uint8Array(buf)
  let bin = ""
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
  return btoa(bin)
}

async function fileToB64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(bufToB64(reader.result))
    reader.onerror = reject
    reader.readAsArrayBuffer(file)
  })
}

function cellText(v) {
  if (v == null) return ""
  if (typeof v === "object") return JSON.stringify(v)
  return String(v)
}

function buildQarColumns(rows) {
  if (qarPreviewColumns && qarPreviewColumns.length) {
    const cols = ["id", "createdAt", ...qarPreviewColumns]
    return Array.from(new Set(cols))
  }
  const cols = ["id", "createdAt"]
  for (const r of rows) {
    const d = r.data || {}
    for (const k of Object.keys(d)) {
      if (!cols.includes(k)) cols.push(k)
    }
  }
  return cols
}

function renderQarPreview(preview) {
  const thead = document.querySelector("#qar-preview-table thead")
  const tbody = document.querySelector("#qar-preview-table tbody")
  thead.innerHTML = ""
  tbody.innerHTML = ""
  const cols = preview.columns || []
  const rows = preview.rows || []
  qarPreviewColumns = cols
  document.getElementById("qar-preview-info").textContent = cols.length ? `列数 ${cols.length} · 预览行数 ${rows.length}` : "未解析到数据"
  if (!cols.length) return
  const trh = document.createElement("tr")
  for (const c of cols) {
    const th = document.createElement("th")
    th.textContent = c
    trh.appendChild(th)
  }
  thead.appendChild(trh)
  for (const r of rows) {
    const tr = document.createElement("tr")
    for (const c of cols) {
      const td = document.createElement("td")
      td.textContent = cellText(r[c])
      tr.appendChild(td)
    }
    tbody.appendChild(tr)
  }
}

function setQarSortHeader(thead) {
  const ths = thead.querySelectorAll("th.sortable")
  ths.forEach(th => {
    th.classList.remove("asc", "desc")
    if (th.dataset.sort === qarSortBy) th.classList.add(qarSortDir === "desc" ? "desc" : "asc")
  })
}

async function refreshQar() {
  try {
    const rows = await TransportCrypto.fetch(`/api/admin/qar-table/rows?sortBy=${encodeURIComponent(qarSortBy)}&sortDir=${encodeURIComponent(qarSortDir)}`, { method: "GET" })
    qarRows = rows || []
    qarColumns = buildQarColumns(qarRows)
    renderQarTable()
  } catch (e) {
    showToast("加载QAR表格失败", e.message, "danger")
  }
}

function renderQarTable() {
  const table = document.getElementById("qar-table")
  const thead = table.querySelector("thead")
  const tbody = table.querySelector("tbody")
  tbody.innerHTML = ""
  document.getElementById("qar-empty-msg").style.display = qarRows.length ? "none" : "block"

  thead.innerHTML = ""
  const trh = document.createElement("tr")
  for (const c of qarColumns) {
    const th = document.createElement("th")
    th.textContent = c
    th.dataset.sort = c
    th.className = "sortable"
    th.addEventListener("click", async () => {
      if (qarSortBy === c) qarSortDir = qarSortDir === "asc" ? "desc" : "asc"
      else { qarSortBy = c; qarSortDir = "asc" }
      await refreshQar()
    })
    trh.appendChild(th)
  }
  thead.appendChild(trh)
  setQarSortHeader(thead)

  for (const r of qarRows) {
    const tr = document.createElement("tr")
    for (const c of qarColumns) {
      const td = document.createElement("td")
      if (c === "id") td.textContent = r.id || ""
      else if (c === "createdAt") td.textContent = (r.createdAt || "").replace("T", " ").replace("Z", "")
      else td.textContent = cellText((r.data || {})[c])
      tr.appendChild(td)
    }
    tbody.appendChild(tr)
  }
}

async function onQarSaveRow() {
  const raw = (document.getElementById("qar-row-json").value || "").trim()
  if (!raw) {
    showToast("缺少数据", "请输入JSON", "danger")
    return
  }
  let data = null
  try {
    data = JSON.parse(raw)
  } catch (e) {
    showToast("格式错误", "JSON无法解析", "danger")
    return
  }
  const id = (document.getElementById("qar-row-id").value || "").trim()
  try {
    if (id) {
      await TransportCrypto.fetch(`/api/admin/qar-table/rows/${encodeURIComponent(id)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ data })
      })
      showToast("已写入", "记录已更新", "success")
    } else {
      await TransportCrypto.fetch("/api/admin/qar-table/rows", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ data })
      })
      showToast("已新增", "记录已创建", "success")
    }
    await refreshQar()
  } catch (e) {
    showToast("保存失败", e.message, "danger")
  }
}

async function onQarPreviewXlsx() {
  const fileInput = document.getElementById("qar-xlsx-file")
  if (!fileInput.files || !fileInput.files.length) {
    showToast("缺少文件", "请选择xlsx文件", "danger")
    return
  }
  const file = fileInput.files[0]
  try {
    const b64 = await fileToB64(file)
    const preview = await TransportCrypto.fetch("/api/admin/qar-table/xlsx/preview-b64?maxRows=20", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filename: file.name, dataBase64: b64 })
    })
    renderQarPreview(preview)
    showToast("预览完成", "已解析xlsx", "success")
  } catch (e) {
    showToast("预览失败", e.message, "danger")
  }
}

async function onQarImportXlsx() {
  const fileInput = document.getElementById("qar-xlsx-file")
  if (!fileInput.files || !fileInput.files.length) {
    showToast("缺少文件", "请选择xlsx文件", "danger")
    return
  }
  const file = fileInput.files[0]
  try {
    const b64 = await fileToB64(file)
    const resp = await TransportCrypto.fetch("/api/admin/qar-table/xlsx/import-b64", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filename: file.name, dataBase64: b64 })
    })
    showToast("导入完成", `已写入 ${resp.imported || 0} 行`, "success")
    await refreshQar()
  } catch (e) {
    showToast("导入失败", e.message, "danger")
  }
}

async function main() {
  const me = await ensureAdmin()
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

  // Person event listeners
  const btnRefreshPersons = document.getElementById("btn-refresh-persons")
  if (btnRefreshPersons) {
    btnRefreshPersons.addEventListener("click", refreshPersons)
  }
  const btnAddPerson = document.getElementById("btn-add-person")
  if (btnAddPerson) {
    btnAddPerson.addEventListener("click", onAddPerson)
  }
  const btnIssuePersonKey = document.getElementById("btn-issue-person-key")
  if (btnIssuePersonKey) {
    btnIssuePersonKey.addEventListener("click", () => {
      const person = allPersons.find(item => item.id === selectedPersonId) || null
      onIssuePersonKey(person)
    })
  }
  const btnTogglePersonAccess = document.getElementById("btn-toggle-person-access")
  if (btnTogglePersonAccess) {
    btnTogglePersonAccess.addEventListener("click", () => {
      const person = allPersons.find(item => item.id === selectedPersonId) || null
      if (!person) {
        showToast("未选择人员", "请先选择一名人员", "danger")
        return
      }
      if (person.accessEnabled) onFreezePersonAccess(person)
      else onRestorePersonAccess(person)
    })
  }
  const searchKeyword = document.getElementById("search-keyword")
  if (searchKeyword) {
    searchKeyword.addEventListener("input", applyPersonFiltersAndSort)
  }
  const filterDateStart = document.getElementById("filter-date-start")
  if (filterDateStart) {
    filterDateStart.addEventListener("change", applyPersonFiltersAndSort)
  }
  const filterDateEnd = document.getElementById("filter-date-end")
  if (filterDateEnd) {
    filterDateEnd.addEventListener("change", applyPersonFiltersAndSort)
  }
  const btnResetFilters = document.getElementById("btn-reset-filters")
  if (btnResetFilters) {
    btnResetFilters.addEventListener("click", () => {
      if (searchKeyword) searchKeyword.value = "";
      if (filterDateStart) filterDateStart.value = "";
      if (filterDateEnd) filterDateEnd.value = "";
      applyPersonFiltersAndSort();
    });
  }

  const sortableHeaders = document.querySelectorAll("table#persons th.sortable")
  if (sortableHeaders) {
    sortableHeaders.forEach(th => {
      th.addEventListener("click", () => {
        const field = th.dataset.sort;
        if (personSortField === field) {
          personSortOrder = personSortOrder === 'asc' ? 'desc' : 'asc';
        } else {
          personSortField = field;
          personSortOrder = 'asc';
        }
        document.querySelectorAll("table#persons th.sortable").forEach(t => t.className = "sortable");
        th.classList.add(personSortOrder);
        applyPersonFiltersAndSort();
      });
    });
  }

  await refreshPersons()
}

main()
