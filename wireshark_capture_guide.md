# Wireshark 捕获 QAR 加密通信指南

## 快速开始

### 1. 启动服务

```bash
# 终端1: 启动本地加密服务
cd local-crypto-service
.\cryptionstart.bat

# 终端2: 启动主业务系统
cd securitysystem/securitysystem
.\mvnw spring-boot:run
```

### 2. 配置Wireshark

#### 捕获过滤器（在开始捕获前设置）
```
tcp.port == 18234 || tcp.port == 8101
```

#### 显示过滤器（捕获后使用）
```
# 查看所有HTTP流量
http

# 仅查看加密服务
tcp.port == 18234 && http

# 仅查看主服务器
tcp.port == 8101 && http

# 查看文件上传
http.request.uri contains "upload"

# 查看加密/解密操作
http.request.uri contains "encrypt" || http.request.uri contains "decrypt"
```

## 捕获场景

### 场景1: 文件上传加密流程

**步骤：**
1. 启动Wireshark捕获（过滤器：`tcp.port == 18234 || tcp.port == 8101`）
2. 浏览器访问 http://localhost:8101/auth.html
3. 使用部署时配置的管理员账号和密码登录系统
4. 进入工作台，上传一个测试文件
5. 停止Wireshark捕获

**预期流量：**

```
[前端] --POST /encrypt--> [本地加密服务:18234]
请求: {"plaintext": "原始文件Base64", "filename": "test.txt"}
响应: {"ciphertext": "密文Base64", "wrappedKey": "封装密钥", "iv": "IV向量"}

[前端] --POST /api/files/upload--> [主服务器:8101]
请求: {"encryptedData": "密文", "wrappedKey": "封装密钥", "originalName": "test.txt"}
响应: {"id": "文件ID", "status": "uploaded"}
```

**验证要点：**
- ✓ 主服务器收到的只有密文（encryptedData）
- ✓ 没有明文传输到主服务器
- ✓ 本地加密服务处理了明文

### 场景2: 文件下载解密流程

**步骤：**
1. 继续Wireshark捕获
2. 在工作台点击下载之前上传的文件
3. 观察流量

**预期流量：**

```
[前端] --GET /api/files/download/{id}--> [主服务器:8101]
响应: {"encryptedData": "密文", "wrappedKey": "封装密钥"}

[前端] --POST /decrypt--> [本地加密服务:18234]
请求: {"ciphertext": "密文", "wrappedKey": "封装密钥"}
响应: {"plaintext": "原始文件Base64"}
```

**验证要点：**
- ✓ 主服务器返回的是密文
- ✓ 解密在本地进行
- ✓ 明文只在用户本地出现

### 场景3: 用户登录流程

**步骤：**
1. 清空Wireshark捕获
2. 重新开始捕获
3. 在登录页面输入账号密码
4. 点击登录

**预期流量：**

```
[前端] --POST /api/auth/login--> [主服务器:8101]
请求: {"emailOrUsername": "admin", "password": "<部署时配置的密码>"}
响应: Set-Cookie: QAR_SESSION=会话令牌
      {"id": "用户ID", "role": "admin"}
```

**安全注意：**
- ⚠ 密码在传输中是明文（建议使用HTTPS）
- ✓ 使用HttpOnly Cookie存储会话

## 分析技巧

### 1. 追踪HTTP流

右键点击任意HTTP包 → 追踪流 → HTTP流

可以看到完整的请求-响应对话。

### 2. 导出对象

文件 → 导出对象 → HTTP

可以导出所有HTTP传输的文件。

### 3. 搜索内容

编辑 → 查找分组

搜索关键词：
- "plaintext" - 查找明文数据
- "ciphertext" - 查找密文数据
- "wrappedKey" - 查找封装密钥
- "encrypt" - 查找加密操作
- "decrypt" - 查找解密操作

### 4. 统计分析

统计 → HTTP → 请求

可以看到所有HTTP请求的统计信息。

## 验证安全性

### 检查清单

```
□ 主服务器（8101）从未收到明文文件内容
□ 主服务器从未收到AES密钥
□ 本地加密服务（18234）的通信仅在本机
□ 会话令牌使用HttpOnly Cookie
□ CSRF令牌正常工作
□ 文件上传下载都经过加密/解密
```

### 常见问题

**Q: 为什么看不到加密前的明文？**
A: 明文只在本地加密服务处理，如果只捕获主服务器流量，看不到明文。

**Q: 如何确认数据已加密？**
A: 查看"encryptedData"字段，应该是Base64编码的随机字符串，无法直接阅读。

**Q: wrappedKey是什么？**
A: 这是经过L-ABE模拟封装的AES密钥，用于安全传输密钥。

## 进阶分析

### 使用tshark命令行工具

```bash
# 实时捕获并显示HTTP内容
tshark -i lo0 -f "tcp port 18234 or tcp port 8101" -Y http

# 提取所有POST请求
tshark -r capture.pcap -Y "http.request.method == POST"

# 提取JSON响应体
tshark -r capture.pcap -Y "http.content_type contains \"json\"" -T fields -e http.file_data
```

### 解码Base64内容

在Wireshark中：
1. 找到包含Base64数据的字段
2. 右键 → 复制 → 值
3. 使用Base64解码工具解码

注意：
- 密文解码后应该是随机字节，无法阅读
- wrappedKey解码后也是加密的密钥材料

## 保存证据

### 导出捕获文件

```
文件 → 另存为 → qar_encryption_test.pcapng
```

### 导出为文本

```
文件 → 导出分组解析结果 → 纯文本
```

### 截图保存

```
1. 选择关键的HTTP流
2. 文件 → 导出 → 图像
```

## 总结

通过Wireshark捕获，可以验证：

1. **明文数据从未传输到服务器** - 主服务器只接收密文
2. **加密在本地完成** - 本地加密服务处理所有加解密
3. **密钥安全传输** - AES密钥经过封装后传输
4. **会话管理安全** - 使用HttpOnly Cookie和CSRF保护

这种架构确保了即使服务器被攻破，攻击者也无法获取用户的明文数据。
