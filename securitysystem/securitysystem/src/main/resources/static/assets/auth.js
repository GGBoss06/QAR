async function switchTab(which) {
  const loginTab = document.getElementById("tab-login")
  const registerTab = document.getElementById("tab-register")
  const loginPanel = document.getElementById("panel-login")
  const registerPanel = document.getElementById("panel-register")
  
  // 移除所有active类
  loginTab.classList.remove("active")
  registerTab.classList.remove("active")
  loginPanel.classList.remove("active")
  registerPanel.classList.remove("active")
  
  // 隐藏所有面板
  loginPanel.style.display = "none"
  registerPanel.style.display = "none"
  
  // 显示当前面板并添加active类
  if (which === "login") {
    loginTab.classList.add("active")
    loginPanel.classList.add("active")
    loginPanel.style.display = "block"
  } else {
    registerTab.classList.add("active")
    registerPanel.classList.add("active")
    registerPanel.style.display = "block"
  }
}

async function refreshMe() {
  const me = await loadMe()
  const logged = document.getElementById("logged-in")
  if (me && me.id) {
    logged.style.display = "flex"
    document.getElementById("me-badge").textContent = "已登录：" + me.emailOrUsername + "（" + me.role + "）"
    document.getElementById("panel-login").style.display = "none"
    document.getElementById("panel-register").style.display = "none"
    return
  }
  logged.style.display = "none"
  await switchTab("login")
}

async function onLogin() {
  const u = (document.getElementById("login-username").value || "").trim()
  const p = document.getElementById("login-password").value || ""
  const btn = document.getElementById("btn-login")
  if (!u) {
    showToast("缺少学号/工号", "请输入学号/工号", "danger")
    return
  }
  if (!p) {
    showToast("缺少密码", "请输入密码", "danger")
    return
  }
  btn.disabled = true
  try {
    await apiFetch("/api/auth/login", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({ emailOrUsername: u, password: p })
    })
    showToast("登录成功", "会话 Cookie 已写入", "success")
    location.href = "/workbench"
  } catch (e) {
    showToast("登录失败", e.message, "danger")
  } finally {
    btn.disabled = false
  }
}

async function onRegister() {
  const u = (document.getElementById("reg-username").value || "").trim()
  const fullName = (document.getElementById("reg-fullname").value || "").trim()
  const idLast4 = (document.getElementById("reg-idlast4").value || "").trim()
  const contact = (document.getElementById("reg-contact").value || "").trim()
  const airline = (document.getElementById("reg-airline").value || "").trim()
  const positionTitle = (document.getElementById("reg-position").value || "").trim()
  const department = (document.getElementById("reg-dept").value || "").trim()
  const p = document.getElementById("reg-password").value || ""
  const p2 = document.getElementById("reg-password2").value || ""
  const btn = document.getElementById("btn-register")
  if (!u) {
    showToast("缺少学号/工号", "请输入学号/工号", "danger")
    return
  }
  if (!fullName) {
    showToast("缺少姓名", "请输入姓名", "danger")
    return
  }
  if (!idLast4 || idLast4.length !== 4) {
    showToast("身份证后四位", "请输入 4 位数字", "danger")
    return
  }
  if (!contact) {
    showToast("缺少联系方式", "请输入联系方式", "danger")
    return
  }
  if (!airline) {
    showToast("缺少航司", "请输入航司", "danger")
    return
  }
  if (!positionTitle) {
    showToast("缺少职位", "请输入所属职位", "danger")
    return
  }
  if (!department) {
    showToast("缺少部门", "请输入所属部门", "danger")
    return
  }
  if (!p) {
    showToast("缺少密码", "请输入密码", "danger")
    return
  }
  if (p !== p2) {
    showToast("两次密码不一致", "请确认两次输入一致", "danger")
    return
  }
  btn.disabled = true
  try {
    await apiFetch("/api/auth/register", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({ emailOrUsername: u, fullName, idLast4, contact, airline, positionTitle, department, password: p, passwordConfirm: p2 })
    })
    showToast("申请已提交", "请等待管理员审核；审批通过后系统将自动签发格密钥材料", "success")
    await switchTab("login")
  } catch (e) {
    showToast("注册失败", e.message, "danger")
  } finally {
    btn.disabled = false
  }
}

async function onLogout() {
  const btn = document.getElementById("btn-logout")
  btn.disabled = true
  try {
    await apiFetch("/api/auth/logout", { method: "POST" })
    showToast("已退出", "会话已失效", "success")
    await refreshMe()
  } catch (e) {
    showToast("退出失败", e.message, "danger")
  } finally {
    btn.disabled = false
  }
}

function togglePassword(inputId, buttonId) {
  const input = document.getElementById(inputId);
  const button = document.getElementById(buttonId);
  const icon = button.querySelector('svg');
  
  if (input.type === 'password') {
    input.type = 'text';
    icon.innerHTML = '<path d="M9.88 9.88a3 3 0 1 0 4.24 4.24L9.88 9.88zm6.94-3.05a8.001 8.001 0 0 1-11.32 0l-2.83 2.83a10.001 10.001 0 0 0 14.14 0l-2.83-2.83z"></path><line x1="2" y1="2" x2="22" y2="22"></line>';
    
    // 点击小眼睛时让所有角色左倾并眼睛向左看
    const pupils = document.querySelectorAll('.pupil, .pupil-inner');
    pupils.forEach(pupil => {
      pupil.style.transform = 'translate(-5px, 0)';
    });
    
    // 添加角色左倾动画效果，所有角色统一向左倾斜
    const chars = document.querySelectorAll('.char');
    chars.forEach((char, index) => {
      const angle = 8 + index * 2; // 8, 10, 12, 14度递增
      char.style.transform = `skewX(${angle}deg)`;
      
      // 添加动态表情
      addDynamicExpression(char, index);
      
      setTimeout(() => {
        char.style.transform = '';
      }, 500);
    });
    
    // 设置密码可见状态
    if (setPasswordVisible) {
      setPasswordVisible(true);
    }
  } else {
    input.type = 'password';
    icon.innerHTML = '<path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z"></path><circle cx="12" cy="12" r="3"></circle>';
    
    // 恢复眼睛位置
    const pupils = document.querySelectorAll('.pupil, .pupil-inner');
    pupils.forEach(pupil => {
      pupil.style.transform = '';
    });
    
    // 恢复密码可见状态
    if (setPasswordVisible) {
      setPasswordVisible(false);
    }
  }
}

// 全局变量，用于控制密码可见状态
let setPasswordVisible; // 用于设置密码可见状态的函数

// 添加动态表情
function addDynamicExpression(char, index) {
  // 根据角色索引添加不同的表情效果
  switch (index) {
    case 0: // 紫色角色
      // 添加微笑表情
      const mouth1 = document.createElement('div');
      mouth1.className = 'dynamic-mouth';
      mouth1.style.cssText = `
        position: absolute;
        width: 60px;
        height: 20px;
        border-radius: 0 0 30px 30px;
        background: #2D2D2D;
        bottom: 80px;
        left: 60px;
        transition: all 0.3s ease;
        z-index: 1000;
      `;
      char.appendChild(mouth1);
      setTimeout(() => {
        mouth1.remove();
      }, 500);
      break;
    case 1: // 黑色角色
      // 添加惊讶表情
      const mouth2 = document.createElement('div');
      mouth2.className = 'dynamic-mouth';
      mouth2.style.cssText = `
        position: absolute;
        width: 30px;
        height: 30px;
        border-radius: 50%;
        background: #2D2D2D;
        bottom: 60px;
        left: 45px;
        transition: all 0.3s ease;
        z-index: 1000;
      `;
      char.appendChild(mouth2);
      setTimeout(() => {
        mouth2.remove();
      }, 500);
      break;
    case 2: // 橙色角色
      // 添加眨眼动画
      const eyes = char.querySelector('.eyes');
      if (eyes) {
        eyes.style.transform = 'scaleY(0.1)';
        setTimeout(() => {
          eyes.style.transform = '';
        }, 200);
      }
      break;
    case 3: // 黄色角色
      // 添加兴奋表情
      char.style.transform += ' scale(1.05)';
      setTimeout(() => {
        char.style.transform = char.style.transform.replace(' scale(1.05)', '');
      }, 500);
      break;
  }
}

// 趣味UI交互逻辑
function initFunUI() {
  let isPasswordVisible = false; // 密码是否可见的标志
  
  const pupils = document.querySelectorAll('.pupil, .pupil-inner');
  const chars = document.querySelectorAll('.char');
  
  // 鼠标移动时只更新瞳孔位置，不倾斜角色
  function onMouseMove(e) {
    // 如果密码可见，不更新瞳孔位置
    if (isPasswordVisible) return;
    
    const mouseX = e.clientX;
    const mouseY = e.clientY;
    
    pupils.forEach(pupil => {
      const eye = pupil.parentElement;
      if (!eye) return;
      
      const eyeRect = eye.getBoundingClientRect();
      const eyeCenterX = eyeRect.left + eyeRect.width / 2;
      const eyeCenterY = eyeRect.top + eyeRect.height / 2;
      
      const dx = mouseX - eyeCenterX;
      const dy = mouseY - eyeCenterY;
      const distance = Math.min(Math.sqrt(dx * dx + dy * dy), 10);
      const angle = Math.atan2(dy, dx);
      
      const maxDistance = pupil.classList.contains('pupil-inner') ? 5 : 3;
      const moveX = Math.cos(angle) * distance * (maxDistance / 10);
      const moveY = Math.sin(angle) * distance * (maxDistance / 10);
      
      pupil.style.transform = `translate(${moveX}px, ${moveY}px)`;
    });
  }
  
  // 添加输入数字时的倾斜效果
  function addNumberInputEffects() {
    const numberInputs = [
      document.getElementById('login-username'),
      document.getElementById('reg-username'),
      document.getElementById('reg-idlast4')
    ];
    
    numberInputs.forEach(input => {
      if (!input) return;
      
      input.addEventListener('input', () => {
        const value = input.value;
        const numberCount = (value.match(/\d/g) || []).length;
        
        // 根据输入的数字数量计算倾斜角度
        const maxAngle = 15;
        const angle = (numberCount / 4) * maxAngle;
        
        // 应用到所有角色，统一向左倾斜
        chars.forEach((char, index) => {
          const charAngle = angle * (0.6 + index * 0.1);
          char.style.transform = `skewX(${charAngle}deg)`;
        });
        
        // 300ms后恢复正常
        clearTimeout(input.timer);
        input.timer = setTimeout(() => {
          chars.forEach(char => {
            char.style.transform = '';
          });
        }, 300);
      });
    });
  }
  
  // 添加随机眨眼动画
  function addBlinkAnimation() {
    const eyes = document.querySelectorAll('.eyes');
    setInterval(() => {
      const randomEye = eyes[Math.floor(Math.random() * eyes.length)];
      if (randomEye) {
        randomEye.style.transform = 'scaleY(0.1)';
        setTimeout(() => {
          randomEye.style.transform = '';
        }, 150);
      }
    }, 3000);
  }
  
  window.addEventListener('mousemove', onMouseMove);
  addNumberInputEffects();
  addBlinkAnimation();
  
  // 返回设置密码可见状态的函数
  return function setPasswordVisible(visible) {
    isPasswordVisible = visible;
  };
}

// 添加密码输入时的趣味效果
function addPasswordEffects() {
  const passwordInputs = [
    document.getElementById('login-password'),
    document.getElementById('reg-password'),
    document.getElementById('reg-password2')
  ];
  
  const purpleChar = document.querySelector('.purple');
  const blackChar = document.querySelector('.black');
  
  passwordInputs.forEach(input => {
    if (!input) return;
    
    input.addEventListener('focus', () => {
      // 所有角色统一向左倾斜
      if (purpleChar) {
        purpleChar.style.transform = 'skewX(12deg)';
      }
      if (blackChar) {
        blackChar.style.transform = 'skewX(10deg)';
      }
    });
    
    input.addEventListener('blur', () => {
      if (purpleChar) {
        purpleChar.style.transform = '';
      }
      if (blackChar) {
        blackChar.style.transform = '';
      }
    });
  });
}

async function main() {
  await initCsrf()
  document.getElementById("tab-login").addEventListener("click", () => switchTab("login"))
  document.getElementById("tab-register").addEventListener("click", () => switchTab("register"))
  document.getElementById("btn-login").addEventListener("click", onLogin)
  document.getElementById("btn-register").addEventListener("click", onRegister)
  document.getElementById("btn-logout").addEventListener("click", onLogout)
  
  // 添加密码切换功能
  document.getElementById("toggle-password").addEventListener("click", () => togglePassword("login-password", "toggle-password"))
  document.getElementById("toggle-reg-password").addEventListener("click", () => togglePassword("reg-password", "toggle-reg-password"))
  document.getElementById("toggle-reg-password2").addEventListener("click", () => togglePassword("reg-password2", "toggle-reg-password2"))
  
  // 初始化趣味UI
  setPasswordVisible = initFunUI();
  addPasswordEffects();
  
  await refreshMe()
}

main()
