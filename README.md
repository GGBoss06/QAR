# QAR 安全系统

面向航空 QAR（快速存取记录器）数据的安全管理研究原型。项目将人员档案、账号审批、文件管理、飞行数据和审计能力整合在一个 Spring Boot 应用中，并采用“**AES-256-GCM 加密文件主体 + Kyber768 封装文件密钥 + 属性策略控制访问**”的混合加密路线。

> 当前项目用于课题研究和功能验证，不应直接作为生产级密码系统使用。网络传输安全由标准 HTTPS/TLS 提供，正式部署必须配置受信任证书。

## 当前实现

- 用户注册、档案匹配、管理员审批和基于角色的访问控制
- 人员基础数据管理，以及 CSV 初始数据导入
- 文件上传、密文存储、授权下载、策略变更和全量导出
- QAR 表格手工录入、Excel 预览/导入和飞行文件预览
- L-ABE 管理视图、属性凭据签发、冻结、恢复与密钥重新封装
- 反馈管理、操作审计和无状态 CSRF 校验
- MySQL 是项目运行和自动测试使用的唯一关系型数据库

## 技术栈

| 层次 | 实现 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.0.3、Spring Security、Spring Data JPA |
| 数据库 | MySQL 8.x |
| 前端 | HTML、CSS、原生 JavaScript、Web Crypto API |
| 文件加密 | AES-256-GCM |
| 密钥封装 | Bouncy Castle Kyber768 KEM |
| 网络传输保护 | HTTPS/TLS（由反向代理、网关或部署平台提供） |
| 表格处理 | Apache POI |
| 构建与测试 | Maven Wrapper、JUnit |

## 数据与密钥存放位置

项目不在 JavaScript 或 Java 代码中硬编码人员、业务文件等基础数据。

| 内容 | 存放位置 |
| --- | --- |
| 用户、人员档案、注册申请、会话摘要 | MySQL |
| 文件元数据、文件密文、密钥信封 | MySQL |
| QAR 表格数据、反馈、审计日志 | MySQL |
| 人员初始种子 | `securitysystem/securitysystem/src/main/resources/person_seed.csv` |
| 本机运行时密码材料 | `securitysystem/securitysystem/data/crypto/` |

`person_seed.csv` 只负责补充数据库中尚不存在的人员记录；系统启动不会覆盖已经维护过的记录。`data/crypto/` 保存本机运行所需的密钥材料，已经加入忽略规则，不应上传到 Git、放入静态资源目录或交给前端。

## 加密方案

### 文件存储

1. 服务端为每个文件生成随机 AES-256 数据密钥（DEK）和随机 GCM IV。
2. 使用 AES-256-GCM 加密文件主体，并把访问策略作为认证数据的一部分绑定到密文。
3. 根据属性策略构造密钥信封，使用属性级 Kyber768 KEM 保护策略秘密。
4. 为满足策略的用户生成独立的 Kyber768 接收者封装。
5. MySQL 仅保存文件密文、必要元数据和 v3 密钥信封，不保存文件明文。

更改策略、冻结账号、恢复账号或更新用户属性时，系统重新封装文件数据密钥，无需重新加密整个文件，因此适合体积较大的 QAR 文件。

### 下载与预览

服务器先校验登录身份、角色和属性策略，再按需恢复文件数据密钥并解密文件。普通下载、管理员预览和导出过程中，服务器内存会短暂出现明文；HTTPS 不能消除服务器被完全控制后的泄露风险。

### 网络传输保护

项目不再实现自定义浏览器密钥握手，所有 API 传输统一依赖标准 HTTPS/TLS。`localhost` 可使用 HTTP 调试；非本机地址的前端请求会拒绝在 HTTP 下发送。生产环境应通过 Nginx、Caddy、网关或云负载均衡器配置受信任证书，并在后端启用 HTTPS 强制跳转。

### 安全边界

- 当前方案是“属性策略 + 用户绑定 KEM”的研究实现，不等同于经过形式化证明和标准化实现审计的 CP-ABE 产品。
- Kyber768 用于保护文件密钥，文件主体仍由高性能的 AES-256-GCM 加密。
- 运行中的服务器持有恢复所需的密钥材料；主机被完全攻陷时，攻击者仍可能取得密钥或捕获解密后的数据。
- 生产化还需要独立 KMS/HSM、密钥轮换、最小权限、主机加固、备份恢复、监控告警和第三方安全审计。

## 快速开始

### 环境要求

- JDK 21
- MySQL 8.x
- Windows PowerShell（使用项目启动脚本时）

Maven 无需单独安装，仓库已包含 Maven Wrapper。

### 1. 准备 MySQL

推荐为应用建立独立数据库和账号。完整 SQL 与测试数据库配置见 [DATABASE.md](./securitysystem/securitysystem/DATABASE.md)。默认连接参数为：

```text
jdbc:mysql://127.0.0.1:3306/qar
```

### 2. 启动应用

在仓库根目录执行：

```powershell
.\start-mysql.ps1
```

也可以双击 `service.bat`。脚本会交互式读取 MySQL 密码和首次部署所需的管理员密码，不会把密码写入仓库。

手动启动方式：

```powershell
$env:APP_DB_USERNAME="qar_app"
$env:APP_DB_PASSWORD="你的数据库密码"
$env:APP_ADMIN_PASSWORD="首次部署使用的管理员密码"
Set-Location .\securitysystem\securitysystem
.\mvnw.cmd spring-boot:run
```

`APP_ADMIN_PASSWORD` 只用于创建初始管理员。数据库中已经存在管理员时可以不再提供。

### 3. 访问页面

应用默认监听 `8101` 端口：

| 页面 | 地址 |
| --- | --- |
| 登录与注册 | <http://localhost:8101/auth> |
| 用户工作台 | <http://localhost:8101/workbench> |
| 管理首页 | <http://localhost:8101/admin> |
| 人员基础数据 | <http://localhost:8101/admin-data.html> |
| QAR 表格管理 | <http://localhost:8101/admin-qar.html> |
| 飞行数据 | <http://localhost:8101/admin-flight.html> |
| L-ABE 管理 | <http://localhost:8101/admin-labe.html> |

初始管理员用户名默认为 `admin`，可通过 `APP_ADMIN_USERNAME` 修改。项目不内置默认管理员密码。

## 常用配置

所有敏感配置都应通过环境变量、部署平台 Secret 或密钥管理系统提供。

| 环境变量 | 作用 | 默认值 |
| --- | --- | --- |
| `APP_DB_URL` | MySQL JDBC 地址 | 本机 `qar` 数据库 |
| `APP_DB_USERNAME` | MySQL 用户名 | `root` |
| `APP_DB_PASSWORD` | MySQL 密码 | 空 |
| `APP_ADMIN_USERNAME` | 初始管理员用户名 | `admin` |
| `APP_ADMIN_PASSWORD` | 初始管理员密码 | 空 |
| `APP_COOKIE_SECURE` | 是否仅通过 HTTPS 发送认证 Cookie | `false` |
| `APP_REQUIRE_HTTPS` | 后端是否强制使用 HTTPS | `false` |
| `APP_FORWARD_HEADERS_STRATEGY` | 是否识别反向代理转发协议头 | `none` |
| `APP_FILE_MAX_BYTES` | 应用层文件大小上限 | `26214400`（25 MiB） |
| `APP_LATTICE_ATTRIBUTE_DIR` | 属性密钥材料目录 | `data/crypto/lattice-attributes` |
| `APP_MIGRATE_LEGACY_ENVELOPES` | 启动时迁移旧密钥信封 | `true` |

本地通过 HTTP 调试时保留默认值。正式 HTTPS 部署必须设置：

```powershell
$env:APP_COOKIE_SECURE="true"
$env:APP_REQUIRE_HTTPS="true"
$env:APP_FORWARD_HEADERS_STRATEGY="framework"
```

## 主要 API

| 模块 | 方法与路径 | 说明 |
| --- | --- | --- |
| 认证 | `POST /api/auth/register` | 提交注册申请 |
| 认证 | `POST /api/auth/login` | 登录 |
| 认证 | `POST /api/auth/logout` | 注销 |
| 认证 | `GET /api/auth/me` | 当前用户信息 |
| 文件 | `POST /api/files` | 上传文件 |
| 文件 | `GET /api/files` | 当前用户文件列表 |
| 文件 | `GET /api/files/{id}/download` | 授权下载 |
| 文件 | `GET /api/files/stats` | 文件统计 |
| 管理 | `GET /api/admin/persons` | 人员档案列表 |
| 管理 | `GET /api/admin/account-requests` | 注册申请列表 |
| 管理 | `POST /api/admin/account-requests/{id}/approve` | 批准申请 |
| 管理 | `POST /api/admin/account-requests/{id}/reject` | 驳回申请 |
| L-ABE | `GET /api/admin/labe/overview` | 加密与授权状态概览 |
| QAR | `POST /api/admin/qar-table/xlsx/import` | 导入 Excel 数据 |

改变服务器状态的请求需要携带系统签发的 CSRF 令牌；管理员接口还要求 `ROLE_ADMIN`。

## 项目结构

```text
QAR/
├── README.md
├── service.bat
├── start-mysql.ps1
└── securitysystem/securitysystem/
    ├── DATABASE.md
    ├── pom.xml
    ├── src/main/java/com/qar/securitysystem/
    │   ├── abe/          # 属性策略与 Kyber 密钥封装
    │   ├── config/       # 应用与安全配置
    │   ├── controller/   # 页面与 API
    │   ├── model/        # JPA 实体
    │   ├── repo/         # 数据访问层
    │   ├── security/     # 认证、CSRF、审计过滤器
    │   ├── service/      # 业务与文件加密服务
    │   └── startup/      # 初始数据与迁移任务
    ├── src/main/resources/
    │   ├── application.properties
    │   ├── person_seed.csv
    │   └── static/
    └── src/test/
```

## 测试

测试使用独立的 MySQL 数据库 `qar_test`，不要连接正式数据库：

```powershell
Set-Location .\securitysystem\securitysystem
$env:TEST_DB_USERNAME="qar_app"
$env:TEST_DB_PASSWORD="你的测试数据库密码"
.\mvnw.cmd test
```

## 故障排查

- `Access denied for user`：检查 `APP_DB_USERNAME`、`APP_DB_PASSWORD` 和 MySQL 授权范围。
- `Communications link failure`：确认 MySQL 服务已启动且端口正确。
- 管理员登录后无权限：确认使用的是数据库中的管理员账号，并检查其角色是否为 `ROLE_ADMIN`。
- 页面提示“必须启用 HTTPS”：使用受信任证书访问正式域名；只有 `localhost` 和回环地址允许 HTTP 调试。
- 中文乱码：确认 MySQL 数据库使用 `utf8mb4`，并保留 JDBC URL 中的 UTF-8 参数。
- 端口 `8101` 被占用：停止旧进程后重新启动应用。

## 安全提交规范

提交代码前请确认未包含以下内容：

- 数据库和管理员真实密码
- `data/crypto/` 下的私钥、用户密钥及历史密钥文件
- 生产数据、上传文件、日志和调试输出
- HTTPS 私钥、云平台凭据或访问令牌

更多数据库说明见 [DATABASE.md](./securitysystem/securitysystem/DATABASE.md)。

## 许可证

本项目仅供学习、课程设计和研究验证使用。
