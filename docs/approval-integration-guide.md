## 结论

CordysCRM 的审批引擎对外只认两个字符串：`formType`（表单类型）和 `resourceId`（业务数据 ID）。

流程配置、实例/任务状态机、加签退回撤回、抄送、评论、状态权限、字段权限全都与业务无关；业务相关性只集中在"适配层"的 6 个方法和几个 `switch` 里。所以二次开发时**不需要改引擎**，只需要把自有实体"注册"进去。

## 三条可选路线

| 路线 | 做法 | 前后端 | 代码量 | 适用 |
| --- | --- | --- | --- | --- |
| A | 业务对象直接做成"自定义表单" | 复用 `/custom-form-data` | 0 行后端 | 字段全动态、能接受 EAV 表结构 |
| **B（推荐）** | 自有表 + 新 `FormKey` 深度接入 | 自己的 API，复用审批相关 API | 1 个 handler + 6 处 switch | 同库、要强 schema、要状态回写/回滚 |
| C | 自有表 + "审批镜像单" + WebHook 回调 | 完全独立服务 | 最松耦合，但要数据双写 | 跨库/微服务，审批只当"闸门" |

如果数据结构是常规强类型表、前端是常规 API，选 B。

## 路线 B 落地清单

### 1. 注册 formType

`backend/crm/src/main/java/cn/cordys/common/constants/FormKey.java` 加一个值，如 `PURCHASE("purchase")`。

必须加：`FormKey.ofKey(formType) == null` 会被引擎当成"自定义表单"（formType 当作 customFormId），走错分支。

### 2. 业务表必需的 6 个列

引擎用 `${sourceTable}` 拼原生 SQL 直接回写（`ExtApprovalInstanceMapper.xml`）：

| 列 | 用途 |
| --- | --- |
| `id` | 主键 |
| `name` | 待办/消息/日志里显示的资源名 |
| `owner` | 评论通知找业务负责人 + 数据权限 |
| `organization_id` | 多组织隔离 |
| `approval_status` | 审批状态回写、状态权限过滤 |
| `approved` | 历史上是否审批通过过；`UPDATE` 降级判定依赖它 |

### 3. 表名映射（静态块，最容易漏）

`ApprovalResourceService.FORM_APPROVAL_TABLE` 的 `static {}` 里加：

```java
FORM_APPROVAL_TABLE.put(FormKey.PURCHASE.getKey(), "purchase");
```

### 4. 实现 `ApprovalResourceHandler`

`backend/crm/src/main/java/cn/cordys/crm/approval/handler/ApprovalResourceHandler.java`，实现类标 `@Service` 即自动注册（`ApprovalResourceService` 在 `@PostConstruct` 里按 `getFormKey()` 收进 `FORM_SERVICE`）。

6 个方法**必须全实现，漏了是静默失效**：

```java
FormKey getFormKey();                                     // 返回 FormKey.PURCHASE
void delete(id, userId, orgId);                           // DELETE 审批通过后真删（引擎已用 ThreadLocal 跳过递归）
void updateSnapshotApprovalStatus(param);                 // 有快照表才需要，没有就空实现
void updateApprovalPostField(param);                      // 审批后字段更新
void revertToSnapshot(id, userId, orgId, snapshotData);    // 驳回/撤回回滚
String getPreUpdateSnapshotData(id, userId, orgId);        // 编辑前快照，返回更新请求 DTO 形态的 JSON
```

两个注意点：

- `getFormKey()` 返回 null 的 handler 只会占一个槽位（`CUSTOM_FORM_SERVICE`），两个 null 会互相覆盖。
- 如果模块有快照表（类似合同/报价/订单的 snapshot），才需要 `hasSnapshot()` 和 `updateSnapshotApprovalStatus`。

### 5. 加 `@HitApproval`（只加注解，业务代码不用改）

```java
@HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.CREATE,
             operatorId = "{#userId}")

@HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.UPDATE,
             resourceId = "{#request.id}", updateType = "{#request.updateType}",
             operatorId = "{#userId}", comment = "{#request.comment}")

@HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.DELETE,
             resourceId = "{#id}", operatorId = "{#userId}")
```

自定义表单用 `formKeyExpr = "{#request.customFormId}"` 代替 `formKey`。

### 6. 补齐所有 switch（不补不报错，只静默降级）

| 文件 | 位置 |
| --- | --- |
| `approval/constants/ApprovalFormTypeEnum.java` | 新增枚举（prefix / permissionId / value） |
| `ApprovalResourceService` | `getLogModuleOfFormKey`、`getReadPermissionByFormType`、`getFormKeyDisplayName` |
| `ApprovalFlowService` | `getFlowFormDisplayName` |
| `ApprovalActionService` | `sendFinishNotice` 里的事件 switch |
| `ApprovalTodoService` | `loadResourceNameMap`、按资源名搜索的 type 别名、名称搜索 |
| `common/service/FieldSourceServiceProvider.java` | `approvalSources`（名称/详情）+ `approvalFields`（字段值） |
| `common/constants/PermissionConstants.java` + 迁移 SQL | 读/改/删权限 |

`FieldSourceServiceProvider` 特别关键：条件分支判断和审批后动作都靠它取业务字段（`ModuleFormService.compressResourceDetail`），不注册则条件永远判空。

### 7. 数据权限

实现 `cn.cordys.common.permission.ResourceAccessContextProvider`（`getFormType()` + `getAccessContext()`）。

审批详情页的查看鉴权走它：参与过审批的人（提审人/审批人/抄送人）直接放行；其他人需要"模块读权限 + 数据权限"。

### 8. 模块表单配置（流程设计器的前提）

条件分支、后置字段更新、字段权限都读 `moduleFormCacheService.getBusinessFormConfig(formType, orgId)`，前端对应 `getDatasourceFieldConfig(formType)`。

也就是你的 formType 需要在 `sys_module_form` 里有一份字段配置（同现有标准模块，走表单设计器或迁移 dml 种一份）。

不做也能跑，但**流程里配不了条件、配不了"审批后改字段"，审批页也没字段可渲染**。

### 9. 前端：直接复用，不用重写审批 UI

- **API**
  - `/approval-flow/get-by-form-type/{formType}`、`/approval-flow/form/options`
  - `/approval-resource/{push|revoke|simple-detail|detail|instance-detail}`
  - `/approval-action/{approve|reject|back|revoke|sign|batch-approve|batch-reject}`
  - `/approval-todo/*`
- **组件**：`frontend/packages/web/src/components/business/crm-approval/crm-approval-detail.vue` 有 `left` 插槽 —— 左边你的单据详情自己渲染，右边（审批链、记录、评论、同意/驳回）整体复用；列表页"审批中"状态用 `crm-approval-popover.vue`。
- **必改**：`frontend/packages/web/src/config/process.ts` 的 `businessTypeOptions` 加一项（否则管理员建不了流程）；待办点击跳转的目标路由要指到你的页面。
- **可选**：`views/system/process/process/components/approval-flow/flow/useConditionFilterConfig.ts` 的 `formTypeConfigMap` 加系统字段过滤器。

## 必须遵守的时序契约

| 时机 | 引擎行为 | 业务侧要做的 |
| --- | --- | --- |
| CREATE | 落库后置 `PENDING`，等用户手动提审 | 新数据默认 `approval_status=NONE`；提供"提交审批"按钮调 `/approval-resource/push` |
| UPDATE | 先存编辑前快照 → 执行更新 → **事务提交后**自动提审 | `getPreUpdateSnapshotData` 返回更新请求形态的 JSON |
| DELETE | 不删，只建一个 DELETE 实例 | `delete()` 里真删，别在里面再挂 `@HitApproval` |
| 驳回/撤回 | 回调 `revertToSnapshot` | 内部调更新时 `updateType="approval"`，否则又触发一次审批 |
| 审批通过 | 引擎直接写你的表的 `approval_status` / `approved` | 业务代码里不要再重复维护这两个字段 |

另外两条隐含规则：

1. `UPDATE` 时若该数据历史上从未审批通过过（`approved=0`），引擎自动降级成 `CREATE`（置 `PENDING` 等手动提审）。
2. 提审必须在原事务提交后、且在**新事务**里执行（`pushAfterCommit`）。原因：`afterCommit` 时连接仍绑在当前线程，普通事务会读到提交前的旧数据，导致条件分支判错。

## 已知坑（按遇到概率排序）

1. `FORM_APPROVAL_TABLE` 是 `static` HashMap，加 formType 只能改代码，无配置入口。
2. 通知事件、日志模块、权限、显示名、待办资源名全是 switch 默认分支 —— 不补不报错，只表现成"待办列表里这行是空的"。
3. `update_fields`（"改了哪些字段"）依赖 `@OperationLog` 的切面上下文，两个切面的 `@Order` 顺序别动。
4. 加签会把 `current_node_id` 加后缀（`SIGN_SPLIT`），任何拿节点 id 查配置的地方先切后缀。
5. 任务只做标记删除（行还在、状态清空），新写的统计别直接 `count(*)`。
6. 一切按 `node_round` 过滤，否则会捞到历史轮次的数据。

## 建议推进顺序

先挑**一个**实体端到端打通：

```
建表 → handler → @HitApproval → 补 switch → 种 module_form
     → 前端流程配置页能选到它 → 新建/编辑/删除/驳回回滚全跑通
```

跑通后再复制。第二个实体的成本会降到"1 个 handler + 5 处 switch + 1 条前端选项"。

## 附：路线 C（松耦合版）

如果只是要"审批作为门槛、业务系统完全独立"（跨库或独立服务）：

1. 用一张自定义表单做"审批镜像单"，业务单据提审前把关键字段写进 `custom_form_data`。
2. 用该自定义表单建流程，审批通过后靠**节点后置配置**的 WebHook 回调你的 API。

代价：数据双写；WebHook 是 `@Async` 且无重试，最终态要自己再查 `/approval-resource/simple-detail/{resourceId}` 对账。
