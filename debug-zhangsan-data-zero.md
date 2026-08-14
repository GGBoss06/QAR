# Debug Session: zhangsan-data-zero

Status: OPEN

## Symptom

- 张三登录后，页面显示“可用数据 0”“可下载数据 0”
- 预期：管理员下发给张三的数据应出现在张三的可访问列表中

## Hypotheses

1. 文件记录虽然存在，但访问策略中的属性与张三当前账号属性不匹配，导致`countAccessibleBy`返回0。
2. 管理端“下发数据”流程没有真正写入`file_record`或写入了错误的接收策略/接收对象。
3. 张三账号虽然能登录，但`personId`或账号绑定的人员档案不正确，导致属性解析落到了错误的人。
4. 前端工作台拉到的是旧服务或旧会话数据，统计接口没有命中新上传记录。
5. 数据库切换后，当前运行服务并没有连到同一个 MySQL 实例，导致管理端写入和用户端读取不在同一库。

## Evidence Plan

- 检查文件统计接口、文件列表接口、属性解析链路、账号与person绑定关系。
- 必要时只添加调试插桩，不先改业务逻辑。

## Instrumentation

- 已在`FilesController.uploadEncrypted`记录管理端下发请求中的目标人员、策略和文件名。
- 已在`FileService.storeEncrypted`记录文件落库后的`recordId/ownerId/policy`。
- 已在`AttributeAuthorityService.canUserAccess`记录张三实际解析出的属性集合和策略求值结果。
- 已在`FileService.canUserAccess/countAccessibleBy`与`FilesController.stats`记录“为什么统计结果为0”。

## Notes

- 当前会话先收集证据，再决定是否做最小修复。

## Evidence Collected

- `8101`端口当前Java进程启动时间为`2026-06-30 17:32:36`，早于本次插桩完成时间，说明用户复现时命中的还是旧服务进程。
- `securitysystem/securitysystem/.dbg/zhangsan-data-zero.env`已生成，但对应`trae-debug-log-zhangsan-data-zero.ndjson`始终未生成，进一步说明当前运行服务没有加载这次新插桩代码。

## Hypothesis Status

- Hypothesis 4：高度怀疑成立。当前至少存在“复现请求没有打到最新服务进程”的问题。
- 其余假设暂未获得运行时证据，需在重启最新服务后继续验证。

## Evidence Update

- 管理端上传日志显示：文件已成功写入数据库，策略为`personNo:20260001 OR role:admin`。
- `AuthController.me`日志显示：张三账号绑定和属性解析正常，包含`personno:20260001`。
- `ApiExceptionHandler`日志显示：张三访问`/api/files`和`/api/files/stats`时，真实异常为`invalid_policy_expression`。

## Root Cause

- `AttributePolicyEvaluator`在`parseExpression/parseTerm`里使用了Java短路求值：
  - `value = value || parseTerm()`
  - `value = value && parseFactor()`
- 当左侧已经确定真假时，右侧解析函数不会执行，导致后续token未被消费，最终被误判为`invalid_policy_expression`。

## Fix

- 改为先解析右侧token，再做布尔合并，确保表达式总能完整消费。
- 新增`AttributePolicyEvaluatorTests`覆盖：
  - 左侧命中时的`OR`
  - 左侧失败时的`AND`
