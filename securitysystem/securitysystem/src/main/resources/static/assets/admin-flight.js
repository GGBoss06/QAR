function cellText(v) {
  if (v == null) return ""
  if (typeof v === "object") return JSON.stringify(v)
  return String(v)
}

const state = {
  files: [],
  fileId: null,
  fileLabel: "",
  sheets: [],
  sheetIndex: 0,
  keyword: "",
  dateStart: "",
  dateEnd: "",
  timeSortDir: "desc"
}

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

function findTimeColumn(cols) {
  const list = cols || []
  const prefers = ["时间", "timestamp", "time", "日期", "date"]
  for (const p of prefers) {
    const c = list.find(x => (x || "").toLowerCase().includes(p))
    if (c) return c
  }
  return null
}

function matchesKeyword(row, keyword) {
  if (!keyword) return true
  const k = keyword.toLowerCase()
  for (const v of Object.values(row || {})) {
    if ((v == null ? "" : String(v)).toLowerCase().includes(k)) return true
  }
  return false
}

function toDateKey(v) {
  if (!v) return ""
  const s = String(v)
  if (s.includes("T")) return s.split("T")[0]
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10)
  if (/^\d{8}$/.test(s)) return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
  if (/^\d{4}\/\d{1,2}\/\d{1,2}/.test(s)) {
    const parts = s.split(/[^\d]+/).filter(Boolean)
    if (parts.length >= 3) {
      const y = parts[0]
      const m = parts[1].padStart(2, "0")
      const d = parts[2].padStart(2, "0")
      return `${y}-${m}-${d}`
    }
  }
  if (/^\d{4}年\d{1,2}月\d{1,2}日/.test(s)) {
    const parts = s.replace("年", "-").replace("月", "-").replace("日", "").split("-")
    if (parts.length >= 3) {
      const y = parts[0]
      const m = (parts[1] || "").padStart(2, "0")
      const d = (parts[2] || "").padStart(2, "0")
      return `${y}-${m}-${d}`
    }
  }
  return ""
}

function timeSortValue(v) {
  if (v == null) return null
  if (typeof v === "number") return v
  const s = String(v)
  const ms = Date.parse(s)
  if (!Number.isNaN(ms)) return ms
  const dk = toDateKey(s)
  if (dk) return Date.parse(dk + "T00:00:00Z")
  return s
}

function matchesDateRow(row, timeKey, startDate, endDate) {
  if (!startDate && !endDate) return true
  if (!timeKey) return true
  const d = toDateKey(row[timeKey])
  if (!d) return false
  if (startDate && d < startDate) return false
  if (endDate && d > endDate) return false
  return true
}

function currentSheet() {
  const sheets = state.sheets || []
  return sheets[state.sheetIndex] || null
}

function renderSheetTabs() {
  const wrap = document.getElementById("sheet-tabs")
  wrap.innerHTML = ""
  const sheets = state.sheets || []
  for (let i = 0; i < sheets.length; i++) {
    const s = sheets[i]
    const b = document.createElement("button")
    b.className = "btn" + (i === state.sheetIndex ? " primary" : "")
    b.textContent = s.name || ("Sheet" + (i + 1))
    b.addEventListener("click", () => {
      state.sheetIndex = i
      renderAll()
    })
    wrap.appendChild(b)
  }
}

function getFilteredSortedRows(sheet) {
  const cols = sheet.columns || []
  const rows = sheet.rows || []
  const timeKey = findTimeColumn(cols)
  const keyword = (state.keyword || "").trim()
  const startDate = state.dateStart || ""
  const endDate = state.dateEnd || ""
  const filtered = rows.filter(r => matchesKeyword(r, keyword) && matchesDateRow(r, timeKey, startDate, endDate))
  if (!timeKey) return filtered
  filtered.sort((a, b) => {
    const va = timeSortValue(a[timeKey])
    const vb = timeSortValue(b[timeKey])
    if (va == null && vb == null) return 0
    if (va == null) return 1
    if (vb == null) return -1
    if (va < vb) return state.timeSortDir === "asc" ? -1 : 1
    if (va > vb) return state.timeSortDir === "asc" ? 1 : -1
    return 0
  })
  return filtered
}

function renderTable() {
  const sheet = currentSheet()
  const table = document.getElementById("qar-table")
  const thead = table.querySelector("thead")
  const tbody = table.querySelector("tbody")
  tbody.innerHTML = ""
  thead.innerHTML = ""

  if (!sheet) {
    document.getElementById("empty-msg").style.display = "block"
    return
  }

  const cols = sheet.columns || []
  const rows = getFilteredSortedRows(sheet)
  document.getElementById("empty-msg").style.display = rows.length ? "none" : "block"

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

function renderMeta(sheet) {
  const el = document.getElementById("sheet-meta")
  if (!sheet) {
    el.textContent = ""
    return
  }
  const total = sheet.totalRows != null ? sheet.totalRows : (sheet.rows || []).length
  const shown = (sheet.rows || []).length
  el.textContent = `总行数 ${total} · 已加载 ${shown}`
}

function renderAll() {
  renderSheetTabs()
  const sheet = currentSheet()
  renderMeta(sheet)
  renderTable()
}

async function refreshFiles() {
  const files = await apiFetch("/api/admin/flight-xlsx/files", { method: "GET" })
  state.files = files || []
  const sel = document.getElementById("file-select")
  sel.innerHTML = ""
  if (!state.files.length) {
    const opt = document.createElement("option")
    opt.value = ""
    opt.textContent = "暂无xlsx文件（请先在工作台上传）"
    sel.appendChild(opt)
    state.fileId = null
    state.sheets = []
    renderAll()
    return
  }

  for (const f of state.files) {
    const opt = document.createElement("option")
    opt.value = f.id
    const t = (f.createdAt || "").replace("T", " ").replace("Z", "")
    opt.textContent = `${f.originalName || f.id} · ${t}`
    sel.appendChild(opt)
  }

  const wanted = state.fileId && state.files.find(x => x.id === state.fileId) ? state.fileId : state.files[0].id
  const orderedIds = [wanted].concat(state.files.map(x => x.id).filter(id => id !== wanted))
  let loaded = false
  let lastError = null
  for (const id of orderedIds) {
    sel.value = id
    state.fileId = id
    const result = await loadPreview(true)
    if (result.ok) {
      loaded = true
      break
    }
    lastError = result.error
  }
  if (!loaded) {
    state.sheets = []
    renderAll()
    if (lastError) {
      showToast("加载失败", lastError.message || "当前文件无法预览", "danger")
    }
  }
}

function normalizePreviewErrorMessage(err) {
  const msg = (err && err.message) || ""
  if (msg === "failed_to_unwrap_lattice_key") {
    return "当前文件的L-ABE密钥无法解封装，可能是历史权威材料不匹配，请重新上传或迁移该文件"
  }
  return msg || "当前文件无法预览"
}

async function loadPreview(silent = false) {
  if (!state.fileId) {
    state.sheets = []
    renderAll()
    return { ok: false, error: new Error("未选择文件") }
  }
  try {
    const resp = await apiFetch(`/api/admin/flight-xlsx/files/${encodeURIComponent(state.fileId)}/preview?maxRows=20000`, { method: "GET" })
    state.fileLabel = resp.originalName || ""
    state.sheets = resp.sheets || []
    state.sheetIndex = 0
    renderAll()
    return { ok: true }
  } catch (e) {
    state.sheets = []
    renderAll()
    const normalized = new Error(normalizePreviewErrorMessage(e))
    if (!silent) {
      showToast("加载失败", normalized.message, "danger")
    }
    return { ok: false, error: normalized }
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

function onResetFilters() {
  document.getElementById("filter-keyword").value = ""
  document.getElementById("filter-date-start").value = ""
  document.getElementById("filter-date-end").value = ""
  state.keyword = ""
  state.dateStart = ""
  state.dateEnd = ""
  renderAll()
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
  
  const btnRefresh = document.getElementById("btn-refresh")
  if (btnRefresh) {
    btnRefresh.addEventListener("click", refreshFiles)
  }
  const btnResetFilters = document.getElementById("btn-reset-filters")
  if (btnResetFilters) {
    btnResetFilters.addEventListener("click", onResetFilters)
  }
  const filterKeyword = document.getElementById("filter-keyword")
  if (filterKeyword) {
    filterKeyword.addEventListener("input", () => {
      state.keyword = filterKeyword.value || ""
      renderAll()
    })
  }
  const filterDateStart = document.getElementById("filter-date-start")
  if (filterDateStart) {
    filterDateStart.addEventListener("change", () => {
      state.dateStart = filterDateStart.value || ""
      renderAll()
    })
  }
  const filterDateEnd = document.getElementById("filter-date-end")
  if (filterDateEnd) {
    filterDateEnd.addEventListener("change", () => {
      state.dateEnd = filterDateEnd.value || ""
      renderAll()
    })
  }
  const btnTimeAsc = document.getElementById("btn-time-asc")
  if (btnTimeAsc) {
    btnTimeAsc.addEventListener("click", () => {
      state.timeSortDir = "asc"
      renderAll()
    })
  }
  const btnTimeDesc = document.getElementById("btn-time-desc")
  if (btnTimeDesc) {
    btnTimeDesc.addEventListener("click", () => {
      state.timeSortDir = "desc"
      renderAll()
    })
  }
  const fileSelect = document.getElementById("file-select")
  if (fileSelect) {
    fileSelect.addEventListener("change", async (e) => {
      state.fileId = e.target.value
      await loadPreview()
    })
  }
  await refreshFiles()
}

main()
