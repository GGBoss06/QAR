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
  if (window.jQuery && jQuery.fn.sidebarMenu) {
    jQuery('.sidebar-menu').sidebarMenu();
  }
  return me
}

async function refreshUsers() {
  const users = await apiFetch("/api/admin/users", { method: "GET" })
  const tbody = document.querySelector("#users tbody")
  tbody.innerHTML = ""
  const totalUsers = document.getElementById("total-users")
  if (totalUsers) totalUsers.textContent = users.length
  for (const u of users) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td><td></td>"
    tr.children[0].textContent = u.emailOrUsername
    tr.children[1].textContent = u.role
    tr.children[2].textContent = (u.createdAt || "").replace("T", " ").replace("Z", "")
    tbody.appendChild(tr)
  }
}

function protectionTypeLabel(status) {
  const labels = {
    ATTRIBUTE_CONTROLLED: "属性访问控制",
    LEGACY_ATTRIBUTE: "旧版属性控制",
    LEGACY_RSA: "旧版加密",
    ENCRYPTED: "已加密",
    UNPROTECTED: "未加密",
  }
  return labels[status] || "未知"
}

async function refreshFiles() {
  const rows = await apiFetch("/api/admin/files", { method: "GET" })
  const tbody = document.querySelector("#files tbody")
  tbody.innerHTML = ""
  const totalData = document.getElementById("total-data")
  if (totalData) totalData.textContent = rows.length
  for (const r of rows) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td><td></td><td></td><td></td><td></td><td></td><td class='actions'></td>"
    tr.children[0].textContent = r.id
    tr.children[1].textContent = r.ownerLabel || r.ownerId
    tr.children[2].textContent = r.originalName
    tr.children[3].textContent = protectionTypeLabel(r.protectionStatus)
    tr.children[4].textContent = fmtBytes(r.sizeBytes)
    tr.children[5].textContent = r.policy || "-"
    tr.children[6].textContent = (r.createdAt || "").replace("T", " ").replace("Z", "")
    const a = document.createElement("a")
    a.className = "btn"
    a.textContent = "下载"
    a.href = "/api/files/" + r.id + "/download"
    const btnPolicy = document.createElement("button")
    btnPolicy.className = "btn primary"
    btnPolicy.textContent = "改策略"
    btnPolicy.addEventListener("click", async () => {
      const nextPolicy = window.prompt("输入新的L-ABE访问策略：", r.policy || "")
      if (nextPolicy === null) return
      try {
        await apiFetch("/api/admin/files/" + r.id + "/policy", {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ policy: nextPolicy })
        })
        showToast("策略已更新", "仅重封装文件AES密钥，未重加密文件内容", "success")
        await refreshFiles()
        await refreshLabeOverview()
      } catch (e) {
        showToast("策略更新失败", e.message, "danger")
      }
    })
    tr.children[7].appendChild(a)
    tr.children[7].appendChild(btnPolicy)
    tbody.appendChild(tr)
  }
}

async function refreshRequests() {
  const rows = await apiFetch("/api/admin/account-requests", { method: "GET" })
  const tbody = document.querySelector("#requests tbody")
  tbody.innerHTML = ""
  const pending = document.getElementById("pending-requests")
  if (pending) pending.textContent = rows.length
  for (const r of rows) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td class='actions'></td>"
    tr.children[0].textContent = r.id
    tr.children[1].textContent = r.personNo
    tr.children[2].textContent = r.fullName
    tr.children[3].textContent = r.airline
    tr.children[4].textContent = r.positionTitle
    tr.children[5].textContent = r.department
    tr.children[6].textContent = r.contact
    tr.children[7].textContent = (r.createdAt || "").replace("T", " ").replace("Z", "")

    const btnApprove = document.createElement("button")
    btnApprove.className = "btn primary"
    btnApprove.textContent = "通过"
    btnApprove.addEventListener("click", async () => {
      const note = window.prompt("审批备注（可选）：", "")
      if (note === null) return
      try {
        await apiFetch("/api/admin/account-requests/" + r.id + "/approve", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ adminNote: note })
        })
        showToast("已通过", "账号已创建：" + r.personNo, "success")
        await refreshRequests()
        await refreshUsers()
      } catch (e) {
        showToast("操作失败", e.message, "danger")
      }
    })

    const btnReject = document.createElement("button")
    btnReject.className = "btn"
    btnReject.textContent = "拒绝"
    btnReject.addEventListener("click", async () => {
      const note = window.prompt("拒绝原因（可选）：", "")
      if (note === null) return
      try {
        await apiFetch("/api/admin/account-requests/" + r.id + "/reject", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ adminNote: note })
        })
        showToast("已拒绝", r.personNo, "success")
        await refreshRequests()
      } catch (e) {
        showToast("操作失败", e.message, "danger")
      }
    })

    tr.children[8].appendChild(btnApprove)
    tr.children[8].appendChild(btnReject)
    tbody.appendChild(tr)
  }
}

async function refreshLogs() {
  const rows = await apiFetch("/api/admin/audit-logs", { method: "GET" })
  const tbody = document.querySelector("#logs tbody")
  tbody.innerHTML = ""
  for (const r of rows) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td><td></td><td></td><td></td><td></td><td></td>"
    tr.children[0].textContent = (r.createdAt || "").replace("T", " ").replace("Z", "")
    tr.children[1].textContent = r.personNo || "-"
    tr.children[2].textContent = r.method || "-"
    tr.children[3].textContent = r.path || "-"
    tr.children[4].textContent = String(r.statusCode || "")
    tr.children[5].textContent = (r.durationMs != null ? (r.durationMs + "ms") : "-")
    tr.children[6].textContent = r.ip || "-"
    tbody.appendChild(tr)
  }
}

function statusBadge(status) {
  const s = (status || "").toLowerCase()
  if (s === "resolved") return "<span class='badge ok'>已解决</span>"
  if (s === "in_progress") return "<span class='badge'>处理中</span>"
  return "<span class='badge warn'>新反馈</span>"
}

async function refreshFeedback() {
  const rows = await apiFetch("/api/admin/feedback", { method: "GET" })
  const tbody = document.querySelector("#feedback tbody")
  tbody.innerHTML = ""
  const pendingFeedback = document.getElementById("pending-feedback")
  if (pendingFeedback) pendingFeedback.textContent = rows.filter(r => (r.status || "").toLowerCase() !== "resolved").length
  for (const r of rows) {
    const tr = document.createElement("tr")
    tr.innerHTML = "<td></td><td></td><td></td><td></td><td></td><td></td><td class='actions'></td>"
    tr.children[0].textContent = r.id
    tr.children[1].textContent = r.ownerId
    tr.children[2].textContent = r.subject || (r.message || "").slice(0, 18) || "(无主题)"
    tr.children[3].textContent = r.type || "-"
    tr.children[4].innerHTML = statusBadge(r.status)
    tr.children[5].textContent = (r.updatedAt || r.createdAt || "").replace("T", " ").replace("Z", "")

    const btnReply = document.createElement("button")
    btnReply.className = "btn"
    btnReply.textContent = "回复"
    btnReply.addEventListener("click", async () => {
      const v = window.prompt("输入回复内容（留空表示清除回复）：", r.adminReply || "")
      if (v === null) return
      try {
        await apiFetch("/api/admin/feedback/" + r.id, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ adminReply: v })
        })
        showToast("已更新", "回复已保存", "success")
        await refreshFeedback()
      } catch (e) {
        showToast("操作失败", e.message, "danger")
      }
    })

    const btnInProgress = document.createElement("button")
    btnInProgress.className = "btn"
    btnInProgress.textContent = "标记处理中"
    btnInProgress.addEventListener("click", async () => {
      try {
        await apiFetch("/api/admin/feedback/" + r.id, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ status: "IN_PROGRESS" })
        })
        await refreshFeedback()
      } catch (e) {
        showToast("操作失败", e.message, "danger")
      }
    })

    const btnResolved = document.createElement("button")
    btnResolved.className = "btn primary"
    btnResolved.textContent = "标记已解决"
    btnResolved.addEventListener("click", async () => {
      try {
        await apiFetch("/api/admin/feedback/" + r.id, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ status: "RESOLVED" })
        })
        await refreshFeedback()
      } catch (e) {
        showToast("操作失败", e.message, "danger")
      }
    })

    tr.children[6].appendChild(btnReply)
    tr.children[6].appendChild(btnInProgress)
    tr.children[6].appendChild(btnResolved)
    tbody.appendChild(tr)
  }
}

async function refreshLabeOverview() {
  try {
    const ov = await apiFetch("/api/admin/labe/overview", { method: "GET" })
    const setText = (id, value) => {
      const el = document.getElementById(id)
      if (el) el.textContent = value
    }
    setText("labe-lattice-files", ov.latticeFiles || 0)
    setText("labe-authority-count", ov.authorityCount || 0)
    setText("labe-bundle-count", ov.userSecretBundles || 0)
    setText("labe-prototype-files", ov.prototypeFiles || 0)
    setText("labe-legacy-files", ov.legacyFiles || 0)
  } catch (e) {
    console.error("Failed to refresh labe overview:", e)
  }
}

async function onExport() {
  showToast("开始导出", "服务器将解密并打包为zip", "success")
  window.location.href = "/api/admin/files/export"
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
  const btnExport = document.getElementById("btn-export")
  if (btnExport) {
    btnExport.addEventListener("click", onExport)
  }
  const btnRefreshFeedback = document.getElementById("btn-refresh-feedback")
  if (btnRefreshFeedback) {
    btnRefreshFeedback.addEventListener("click", refreshFeedback)
  }
  const btnRefreshRequests = document.getElementById("btn-refresh-requests")
  if (btnRefreshRequests) {
    btnRefreshRequests.addEventListener("click", refreshRequests)
  }
  const btnRefreshLogs = document.getElementById("btn-refresh-logs")
  if (btnRefreshLogs) {
    btnRefreshLogs.addEventListener("click", refreshLogs)
  }
  
  await refreshUsers()
  await refreshRequests()
  await refreshFiles()
  await refreshFeedback()
  await refreshLogs()
  await refreshLabeOverview()
}

main()
