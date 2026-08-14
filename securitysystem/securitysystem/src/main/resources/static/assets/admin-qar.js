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

const state = { sortBy: "createdAt", sortDir: "desc", columns: [] }

function setSortHeader(thead, sortBy, sortDir) {
  const ths = thead.querySelectorAll("th.sortable")
  ths.forEach(th => {
    th.classList.remove("asc", "desc")
    if (th.dataset.sort === sortBy) th.classList.add(sortDir === "desc" ? "desc" : "asc")
  })
}

function buildColumns(rows) {
  const cols = ["id", "createdAt"]
  for (const r of rows) {
    const d = r.data || {}
    for (const k of Object.keys(d)) {
      if (!cols.includes(k)) cols.push(k)
    }
  }
  return cols
}

function cellText(v) {
  if (v == null) return ""
  if (typeof v === "object") return JSON.stringify(v)
  return String(v)
}

async function refresh() {
  const rows = await apiFetch(`/api/admin/qar-table/rows?sortBy=${encodeURIComponent(state.sortBy)}&sortDir=${encodeURIComponent(state.sortDir)}`, { method: "GET" })
  const table = document.getElementById("qar-table")
  const thead = table.querySelector("thead")
  const tbody = table.querySelector("tbody")
  tbody.innerHTML = ""

  document.getElementById("empty-msg").style.display = rows.length ? "none" : "block"
  state.columns = buildColumns(rows)

  thead.innerHTML = ""
  const trh = document.createElement("tr")
  for (const c of state.columns) {
    const th = document.createElement("th")
    th.textContent = c
    th.dataset.sort = c
    th.className = "sortable"
    th.addEventListener("click", async () => {
      if (state.sortBy === c) state.sortDir = state.sortDir === "asc" ? "desc" : "asc"
      else { state.sortBy = c; state.sortDir = "asc" }
      await refresh()
    })
    trh.appendChild(th)
  }
  thead.appendChild(trh)
  setSortHeader(thead, state.sortBy, state.sortDir)

  for (const r of rows) {
    const tr = document.createElement("tr")
    for (const c of state.columns) {
      const td = document.createElement("td")
      if (c === "id") td.textContent = r.id || ""
      else if (c === "createdAt") td.textContent = (r.createdAt || "").replace("T", " ").replace("Z", "")
      else td.textContent = cellText((r.data || {})[c])
      tr.appendChild(td)
    }
    tbody.appendChild(tr)
  }
}

async function onSaveRow() {
  const raw = (document.getElementById("row-json").value || "").trim()
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
  const id = (document.getElementById("row-id").value || "").trim()
  try {
    if (id) {
      await apiFetch(`/api/admin/qar-table/rows/${encodeURIComponent(id)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ data })
      })
      showToast("已写入", "记录已更新", "success")
    } else {
      await apiFetch("/api/admin/qar-table/rows", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ data })
      })
      showToast("已新增", "记录已创建", "success")
    }
    await refresh()
  } catch (e) {
    showToast("保存失败", e.message, "danger")
  }
}

function renderPreview(preview) {
  const thead = document.querySelector("#preview-table thead")
  const tbody = document.querySelector("#preview-table tbody")
  thead.innerHTML = ""
  tbody.innerHTML = ""
  const cols = preview.columns || []
  const rows = preview.rows || []
  document.getElementById("preview-info").textContent = cols.length ? `列数 ${cols.length} · 预览行数 ${rows.length}` : "未解析到数据"
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

async function onPreviewXlsx() {
  const fileInput = document.getElementById("xlsx-file")
  if (!fileInput.files || !fileInput.files.length) {
    showToast("缺少文件", "请选择xlsx文件", "danger")
    return
  }
  const file = fileInput.files[0]
  try {
    const b64 = await fileToB64(file)
    const preview = await apiFetch("/api/admin/qar-table/xlsx/preview-b64?maxRows=20", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filename: file.name, dataBase64: b64 })
    })
    renderPreview(preview)
    showToast("预览完成", "已解析xlsx", "success")
  } catch (e) {
    showToast("预览失败", e.message, "danger")
  }
}

async function onImportXlsx() {
  const fileInput = document.getElementById("xlsx-file")
  if (!fileInput.files || !fileInput.files.length) {
    showToast("缺少文件", "请选择xlsx文件", "danger")
    return
  }
  const file = fileInput.files[0]
  try {
    const b64 = await fileToB64(file)
    const resp = await apiFetch("/api/admin/qar-table/xlsx/import-b64", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ filename: file.name, dataBase64: b64 })
    })
    showToast("导入完成", `已写入 ${resp.imported || 0} 行`, "success")
    await refresh()
  } catch (e) {
    showToast("导入失败", e.message, "danger")
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
  
  document.getElementById("btn-refresh").addEventListener("click", refresh)
  document.getElementById("btn-save-row").addEventListener("click", onSaveRow)
  document.getElementById("btn-preview").addEventListener("click", onPreviewXlsx)
  document.getElementById("btn-import").addEventListener("click", onImportXlsx)
  await refresh()
}

main()

