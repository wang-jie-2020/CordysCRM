# CordysCRM 通知模块设计

> 对应代码：`backend/crm/src/main/java/cn/cordys/crm/system/notice/**`，
> 配套：`system/service/NotificationService.java`、`MessageNotificationService.java`、`system/job/NoticeExpireJob.java`
> 前端：`packages/web/src/views/system/message/**`（消息设置）、`store/modules/app`（SSE 订阅）
> 结论基于 2026-11 的代码阅读。

## 一、这个模块解决什么

一件事：**"谁在什么事件下，通过哪些渠道，收到什么文案"**。

它被拆成四段，每段都能单独替换：

```
业务事件(module + event)
   → 消息通道选择（按组织配置的 sys_message_task）
      → 站内信 / 邮件 / 企微 / 钉钉 / 飞书
         → 站内信再落库 + 缓存 + SSE 实时推送
```

关键设计：**渠道开关是配置数据，不是代码分支**。某个事件不想发邮件，去管理后台关掉即可，不用改业务代码。

## 二、概念模型

| 概念 | 说明 |
|---|---|
| module | 业务模块（客户/线索/商机/合同/订单/审批/公告/系统…），决定"去哪张消息设置里找配置" |
| event | 具体事件（新建客户、待办提醒、审批完结…），决定"发哪一条" |
| 渠道 | 站内信、邮件、企业微信、钉钉、飞书，各自一个 `XxxNoticeSender` |
| 接收人 | `Receiver(userId, type)` 列表；站内信会再按组织过滤成真实用户 |
| 模板 | 事件对应的默认文案；支持带占位符的 `${xxx}` |
| 文案 | 消息分类的展示名（如"客户管理"） |

**分发模型**：一个事件可能对应多条 `sys_message_task` 记录（不同渠道组合），发送时先查配置、再按配置逐条下发。

## 三、数据模型

| 表 | 说明 |
|---|---|
| `sys_message_task` | 核心配置。键是 `(task_type=module, event, organization_id)`，带 5 个渠道开关 + 自定义模板（`template` 二进制列） |
| `sys_message_task_config` | 同一事件的附加配置 JSON：额外接收人、角色、是否通知负责人、向上几级、到期提醒时间等。目前主要被定时任务使用 |
| `notification` | 站内信实体。`receiver`、`type`（系统通知/公告）、`status`（已读/未读）、`resource_type`、`resource_id`、`content`、`operator` |
| `announcement` | 公告，走另一套 Redis key，但推送通道相同 |

事件的枚举、默认模板的 i18n key 全部集中在 `NotificationConstants`（`Module` / `Event` / `TemplateText` 三个内部接口），默认文案在 `resources/i18n/cordys-crm_zh_CN.properties`。

## 四、发送链路

业务代码只有一个入口：

```java
commonNoticeSendService.sendNotice(
        module, event, paramMap, operatorId, orgId, userIds, excludeSelf);
```

内部执行：

```
1. 按操作人语言设置 Locale
2. 查配置：该 (module, org) 下所有"已启用"的消息任务，按 (taskType, event) 分组
3. 过滤出 event 匹配的配置行
4. 逐行判断渠道开关，分别调用对应 sender：
     sysEnable      → 站内信
     emailEnable    → 邮件
     weComEnable    → 企业微信
     dingTalkEnable → 钉钉
     larkEnable     → 飞书
```

几处细节：

- **异步**：整条链路在 `@Async("threadPoolTaskExecutor")` 线程池执行，业务接口不会被邮件拖慢。
- **第三方渠道是软依赖**：企微/钉钉/飞书的 sender 通过 Bean 工厂按需取，没装那个集成模块就只打一条 warn，不报错。所以"配置开了但没收到"先查集成模块是否部署。
- **配置查询失败时返回空列表**并记录错误，不会把业务事务带崩。
- **收件人过滤**：站内信发出前会把收件人过滤成"当前组织的真实用户"（`admin` 是特例，始终保留）；`excludeSelf` 用来排除操作人自己。

## 五、站内信与实时推送

这是整个模块最"重"的一段，值得单独理解。

### 5.1 写入

每个接收人执行一次：

```
1. notification 表插入一行（未读）
2. Redis ZSet  msg_user:{userId}  加一条 {消息ID, 时间戳}      ← 用户的消息索引
3. Redis KV    msg_content:{消息ID} = 消息 JSON             ← 消息体
4. 只保留最新 5 条：超出的内容 key 删除、ZSet 成员删除
5. Redis KV    user_read:{userId} = "False"                ← 用户还有未读
6. 发布 Redis 主题 sse-topic：{ userId, noticeType }
```

**"只保留 5 条"是硬编码**：前端顶部铃铛永远只展示最近 5 条。完整列表走消息中心的数据库查询。

### 5.2 推送

```
Redis 主题 sse-topic
  → SSEConsumer 消费
    → SseService.broadcastPeriodically(userId, noticeType)
      → 组装 SseMessageDTO { 消息列表, 公告列表, read }
        → 写入该用户所有在线 SSE 连接的 Sink
```

`SseMessageDTO` 三个字段：`notificationDTOList`、`announcementDTOList`、`read`。

消息列表的取法有个回退逻辑：先读 Redis ZSet（最多 5 条），**如果为空（或全是别的组织的数据），再回查数据库**最近的消息。所以多组织切换时不会出现"红点在但没有内容"。

### 5.3 连接管理

- 订阅接口 `/sse/subscribe`，身份**只认登录会话**；请求里带的 userId 仅用于兼容老客户端，和会话不一致直接 403。
- 连接 key = `(organizationId, sessionId, clientId)`，同一用户最多 2 条连接，超限拒绝（不会踢掉别人的会话）。
- 服务端每 15 秒推一次心跳，前端解析到 `HEARTBEAT` 直接忽略。
- 关闭时用 `takeUntilOther` 把心跳定时器一起终止，避免泄漏。

### 5.4 已读

```
点单条已读 / 全部已读
  → 更新数据库状态
  → 清掉该用户的 Redis 消息索引与消息体
  → 若已无未读，置 user_read:{userId} = "True"
  → 发布 sse-topic（noticeType = READ）
```

前端只依赖 `read` 标记刷新红点，不重新拉列表。

## 六、模板与国际化

优先级从高到低：

1. 调用方指定 `useTemplate=true` + `templateKey` → 用翻译后的自定义文案（评论 @ 就用的这个）；
2. 管理后台为该事件配置的自定义模板；
3. 事件的默认模板（`{EVENT}_TEXT` 对应的 i18n 文案）；
4. 兜底：`事件显示名 + "通知"`。

占位符用 `StringSubstitutor` 渲染，`paramMap` 的 key 直接对应 `${key}`。另有两个隐式转换：

- key 以 `Time` 结尾 → 按毫秒时间戳格式化成 `yyyy-MM-dd HH:mm:ss`；
- key 以 `User` 结尾 → 当作手机号/邮箱反查用户名。

这几条约定让"传 paramMap"变得很省事，但也意味着**参数命名不是随便起的**。

## 七、配置界面

| 界面 | 作用 |
|---|---|
| 消息设置（`views/system/message`） | 按模块 × 事件列出 5 个渠道开关，可批量开关；可编辑单条模板 |
| 到期设置抽屉 | 配置到期提醒的接收人（指定人/角色/负责人向上几级）与提醒时间点 |
| 公告 | 独立 tab |

后端接口集中在 `MessageTaskController`（`/message/task/save|batch/save|get|config/query`），权限位 `SYSTEM_NOTICE_*`。

**可见模块是白名单**：`SendModuleService.getNoticeModules()` 只返回客户/线索/商机/合同 + 系统。新模块要在消息中心出现，这里和 `NotificationService` 的计数逻辑都要加。

## 八、定时通知

`NoticeExpireJob` 每天 8 点跑一次，处理三类到期提醒：报价单、合同、回款计划（即将到期 + 已到期）。

接收人的算法在 `CommonNoticeSendService.getNoticeReceiveUserIds`：

```
显式指定的人 + 指定角色下的所有人 + 负责人的 N 级部门负责人
```

（"负责人向上几级"依赖 `sys_message_task_config` 里的配置。）

## 九、扩展点

### 9.1 新增一个通知事件

1. `NotificationConstants.Event` 加常量，`TemplateText` 加 `XXX_TEXT`；
2. 两个 i18n 文件加 `message.xxx`（事件名）和 `message.xxx_text`（默认文案）；
3. 业务代码一行 `sendNotice(module, event, paramMap, ...)`；
4. 消息任务配置会按常量自动补齐默认行，不用手写 SQL。

### 9.2 新增一个渠道

1. 继承 `AbstractNoticeSender`，在 `send()` 里复用 `getContext` / `getSubjectText`；
2. 在 `NoticeSendService.sendNotification` 里加一个 `if (md.isXxxEnable())`；
3. `MessageTask` / `MessageDetailDTO` 加开关字段，前端消息设置表加一列。

### 9.3 新增接收人规则

改 `CommonNoticeSendService.getNoticeReceiveUserIds`，配置从 `MessageTaskConfig` 的 JSON 里读。

## 十、已知约束与坑

1. **`@SendNotice` 注解 + `SendNoticeAspect` 是死代码**：全仓库没有一处使用。所有通知实际都走 `CommonNoticeSendService`，别照着它写。
2. **站内信只缓存 5 条**：涉及"最近消息"的需求先确认这个上限，改上限要同时改写入与清理两处。
3. **第三方渠道静默跳过**：Bean 不存在只 warn 不抛错。
4. **模块可见性是白名单**：新业务模块不加 `SendModuleService`，消息中心看不到，用户也就没法开关。
5. **`paramMap` 的 key 有隐式语义**（`*Time` / `*User`），拼错不会报错，只会显示原始值。
6. **异步 + ThreadLocal 语言**：语言是在异步方法内部设置的，嵌套异步会丢语言，多语言场景要注意。
7. **公告与系统通知是两套 Redis key**（`announce_user:` / `msg_user:`），做统计或清理时别混。
