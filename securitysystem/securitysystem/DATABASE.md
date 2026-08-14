# MySQL 数据库配置

项目统一使用 MySQL，不再依赖其他数据库进行运行或测试。

## 1. 环境要求

- MySQL 8.x
- 默认端口 `3306`
- 数据库字符集建议使用 `utf8mb4`

## 2. 创建数据库和应用账号

使用 MySQL 管理员账号执行：

```sql
CREATE DATABASE IF NOT EXISTS qar
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS qar_test
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'qar_app'@'localhost' IDENTIFIED BY '请替换为强密码';
GRANT ALL PRIVILEGES ON qar.* TO 'qar_app'@'localhost';
GRANT ALL PRIVILEGES ON qar_test.* TO 'qar_app'@'localhost';
FLUSH PRIVILEGES;
```

`qar` 用于正常运行，`qar_test` 仅用于自动测试。不要让测试连接正式数据库。

## 3. 启动项目

在 PowerShell 中进入项目目录并设置当前终端使用的数据库凭据：

```powershell
$env:APP_DB_USERNAME="qar_app"
$env:APP_DB_PASSWORD="你的强密码"
.\mvnw.cmd spring-boot:run
```

默认连接地址为 `jdbc:mysql://127.0.0.1:3306/qar`。如果地址、端口或库名不同，可以覆盖完整连接地址：

```powershell
$env:APP_DB_URL="jdbc:mysql://127.0.0.1:3306/qar?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false"
```

启动成功后访问 `http://localhost:8101/auth.html`。

## 4. 运行测试

测试使用独立的 `qar_test` 数据库：

```powershell
$env:TEST_DB_USERNAME="qar_app"
$env:TEST_DB_PASSWORD="你的强密码"
.\mvnw.cmd test
```

也可以通过 `TEST_DB_URL` 指定其他 MySQL 测试实例。

## 5. 常见问题

- `Communications link failure`：确认 MySQL 服务已启动、端口为 `3306`。
- `Access denied`：检查用户名、密码及账号允许登录的主机。
- `Unknown database`：手动创建数据库，或给账号授予创建数据库的权限。
- 中文乱码：确认数据库为 `utf8mb4`，并保留 JDBC 地址中的 UTF-8 参数。
- 端口占用：确认应用端口 `8101` 没有被旧进程占用。

生产环境应启用 TLS，并通过环境变量或密钥管理系统提供密码，不要把真实密码写入仓库。
