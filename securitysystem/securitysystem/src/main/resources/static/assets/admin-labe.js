let labePersons = []
let selectedLabePersonId = null

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
    if (spanEl) spanEl.textContent = me.emailOrUsername + " · " + me.role
  }
  if (window.jQuery && jQuery.fn.sidebarMenu) {
    jQuery(".sidebar-menu").sidebarMenu()
  }
  return me
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

function badgeText(ok, yesText, noText) {
  return ok ? `<span class="badge ok">${yesText}</span>` : `<span class="badge warn">${noText}</span>`
}

async function refreshOverview() {
  const ov = await apiFetch("/api/admin/labe/overview", { method: "GET" })
  const setText = (id, value) => {
    const el = document.getElementById(id)
    if (el) el.textContent = value
  }
  setText("ov-total-files", ov.totalFiles || 0)
  setText("ov-lattice-files", ov.latticeFiles || 0)
  setText("ov-authorities", ov.authorityCount || 0)
  setText("ov-bundles", ov.userSecretBundles || 0)
}

async function refreshAuthorities() {
  const rows = await apiFetch("/api/admin/labe/authorities", { method: "GET" })
  const tbody = document.querySelector("#labe-authorities-table tbody")
  tbody.innerHTML = ""
  for (const row of rows) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td>"
    tr.children[0].textContent = row.authorityId
    tr.children[1].textContent = (row.attributeScopes || []).join(", ")
    tbody.appendChild(tr)
  }
}

async function refreshPersons() {
  labePersons = await apiFetch("/api/admin/labe/persons", { method: "GET" })
  renderPersonsTable()
  renderSelectedPerson()
}

function renderPersonsTable() {
  const tbody = document.querySelector("#labe-persons-table tbody")
  tbody.innerHTML = ""
  if (!selectedLabePersonId && labePersons.length) selectedLabePersonId = labePersons[0].id
  for (const row of labePersons) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td><td></td><td></td><td></td><td></td><td></td>"
    tr.children[0].textContent = row.personNo || "-"
    tr.children[1].textContent = row.fullName || "-"
    tr.children[2].textContent = row.department || "-"
    tr.children[3].textContent = row.personCategory || "-"
    tr.children[4].textContent = row.clearanceLevel || "-"
    tr.children[5].innerHTML = badgeText(row.accountReady, "已建", "未建")
    tr.children[6].innerHTML = badgeText(row.secretBundleReady, "已生成", "未生成")
    tr.classList.toggle("selected", row.id === selectedLabePersonId)
    tr.addEventListener("click", () => {
      selectedLabePersonId = row.id
      renderSelectedPerson()
      renderPersonsTable()
    })
    tbody.appendChild(tr)
  }
}

function renderSelectedPerson() {
  const row = labePersons.find(item => item.id === selectedLabePersonId)
  const summary = document.getElementById("labe-person-summary")
  if (!summary) return
  if (!row) {
    summary.textContent = "请选择左侧人员查看详情。"
    renderChipList("labe-person-attributes", [])
    return
  }
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
  name.textContent = row.fullName || "-"
  appendLine(name, `（${row.personNo || "-"}）`)
  appendLine(`部门：${row.department || "-"}，航司：${row.airline || "-"}`)
  appendLine(`职责域：${row.dutyDomain || "-"}，机队：${row.fleetGroup || "-"}`)
  appendLine(`账号状态：${row.accountReady ? "已建立用户账号" : "尚未建立用户账号"}`)
  appendLine(`授权状态：${row.secretBundleReady ? "已授权" : "尚未授权"}`)
  renderChipList("labe-person-attributes", row.attributes || [])
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
  const btnLogout = document.getElementById("btn-logout")
  if (btnLogout) btnLogout.addEventListener("click", onLogout)
  await refreshOverview()
  await refreshAuthorities()
  await refreshPersons()
}

main()
