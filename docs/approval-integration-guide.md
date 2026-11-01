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

新表默认值：`approval_status` 用 `NONE`、`approved` 用 `0`。

两个数据侧注意点：

- **存量表补列要回填**：`UPDATE t SET approved = 1 WHERE approval_status = 'APPROVED'`（参考 `V1.7.2_2_1__data.sql`）。不回填的老数据会被当成"从未审批通过过"，编辑时走 CREATE 降级。
- **`owner` 必须有值**：评论通知靠它找业务负责人（`ApprovalCommentNoticeService`），为空的资源收不到评论提醒。

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

`formKey` 有默认值 `FormKey.ORDER`（不是必填），写漏了不报错、静默按订单走，注解一定要显式写。

### 6. 补齐注册点

分两类：6.1~6.3 是**硬卡点**（不做直接报错或审批功能不可用）；6.4 的 switch 才是不补就静默降级。

#### 6.1 `validateFormType` 白名单 —— 不补则建流程直接失败

`ApprovalFlowService.validateFormType` 用**硬编码枚举数组**放行标准表单，不走 `ApprovalFormTypeEnum` 遍历，所以 6.4 里加了枚举**不够**：

```java
for (ApprovalFormTypeEnum type : new ApprovalFormTypeEnum[] {
        CLUE, CUSTOMER, OPPORTUNITY, QUOTATION, CONTRACT, INVOICE, ORDER }) { ... }
```

不加进去 → `POST /approval-flow` 把你的 formType 当自定义表单 ID 去库里查，查不到抛 `module.form.illegal`。报错完全指不到这里，是最费时半天的一处。

#### 6.2 `FieldSourceServiceProvider` 是四层注册，不是两行 put

从上到下四层，缺任一层条件分支永远判空：

1. `crm/system/constants/FieldSourceType.java` 加枚举（值 = 业务表名）
2. `sources.put(FieldSourceType.PURCHASE, new SourceReader(purchaseService::getSimple, purchaseService::batchGetSimpleByIds))`
3. 业务 service 提供 `getSimple(String)` / `batchGetSimpleByIds(List<String>)` / `getFieldValues(String)` 三个方法
4. 最后才是 `approvalSources.put(FormKey.PURCHASE.getKey(), sources.get(FieldSourceType.PURCHASE))` + `approvalFields.put(..., purchaseService::getFieldValues)`

条件分支判断和审批后动作都靠它取业务字段（`ModuleFormService.compressResourceDetail`），不注册则条件永远判空。

#### 6.3 审批结果通知：3 个常量 + 1 条数据

`ApprovalActionService.sendFinishNotice` 的 switch 只是第一步，缺下面任一项通知就静默不发（发送方 catch 掉异常，无日志）：

1. `NotificationConstants.Module` 加 module（= `sys_message_task.task_type`）
2. `NotificationConstants.Event` 加事件
3. `NotificationConstants.TemplateText` 加 `Xxx_TEXT` 字段 + i18n `message.xxx` 模板正文（`MessageTemplateUtils` 按 `{event}_TEXT` 取）
4. 迁移 dml 往 `sys_message_task` 种一行 `(event, task_type, sys_enable = 1)`——`MessageDetailService` 按 `(module, event)` 查这张表，没配置直接不发

#### 6.4 其他注册点（不补不报错，只静默降级）

| 文件 | 位置 |
| --- | --- |
| `crm/src/main/resources/permission.json` | 权限树定义，`ApprovalFormTypeEnum.permissionId` 必须与这里的节点 `id` 一致（如 `CONTRACT`），否则审批权限下拉为空 |
| `common/constants/FormKeyConstants.java` | `@CsPermission(formType = ...)` 用的字符串常量 |
| `common/constants/PermissionConstants.java` + 迁移 SQL | 读/改/删权限常量 + `sys_role_permission` 种子 |
| `approval/constants/ApprovalFormTypeEnum.java` | 新增枚举（prefix / permissionId / value） |
| `ApprovalResourceService` | `getLogModuleOfFormKey`、`getReadPermissionByFormType`、`getFormKeyDisplayName` |
| `ApprovalFlowService` | `getFlowFormDisplayName` |
| `ApprovalActionService` | `sendFinishNotice` 的事件 switch + **`getLogModuleOfFormKey`（私有，另有一份实现）** |
| `ApprovalCommentNoticeService` | 评论通知的 `resourceType` switch（`default` 会静默落到 `Module.APPROVAL`） |
| `ApprovalTodoService` | `loadResourceNameMap`、按资源名搜索的 type 别名、名称搜索 |
| `framework/.../LogModule.java` | 日志模块常量（`getLogModuleOfFormKey` 的返回值） |

`getLogModuleOfFormKey` 有两份：`ApprovalResourceService`（public）和 `ApprovalActionService`（private），只补一处会漏日志模块。

注意：上面这些里大部分确实是静默降级，但 `FormKey` 枚举一加，**两处穷举 switch 会直接编译失败**（`McpFormulaController`、`StandardRelatedDetailTabDataHandler`）——这反而是好事，`mvn compile` 会替你揪出来。其余非穷举的地方仍会静静降级。

### 7. 数据权限

实现 `cn.cordys.common.permission.ResourceAccessContextProvider`，三个方法都要（`batchGetOwnerIds` 是抽象方法不是 default，少写编译不过）：

- `getFormType()` —— 返回 formType 字符串
- `getAccessContext(resourceId, orgId)` —— 返回 `ownerId` + `approvalStatus`，资源不存在返回 `null`
- `batchGetOwnerIds(resourceIds, orgId)` —— 批量查负责人，返回 `资源id → owner` 映射

组织共享、没有负责人的资源重写 `requiresOwner()` 返回 `false`。

审批详情页的查看鉴权走它：参与过审批的人（提审人/审批人/抄送人）直接放行；其他人需要"模块读权限 + 数据权限"。

### 8. 模块表单配置（条件、后置字段的硬前提）

条件分支、后置字段更新、字段权限都读 `moduleFormCacheService.getBusinessFormConfig(formType, orgId)`，前端对应 `getDatasourceFieldConfig(formType)`。

也就是你的 formType 必须在 `sys_module_form` + `sys_module_form_blob`（以及 `sys_module_field*`）里有完整一份字段配置。

**没有它不是“少点功能”，而是流程存不进去**：只要 CREATE/UPDATE/DELETE 任一节点配置里出现条件节点或后置字段，保存流程就走 `UserViewService.buildOptionMap` → `ModuleFormService.getModuleFormByKey`，查不到直接抛 404「资源不存在」，而报错完全指不到这一层。

种配置：走前端表单设计器最省事；纯后端验证可直接拷一份现有模块的四个表行（`sys_module_form` / `sys_module_form_blob` / `sys_module_field` / `sys_module_field_blob`）改 `form_key` 和主键。注意 MySQL 里 `varchar` 主键与数字字面量比较会因隐式转换误匹配多行，务必加引号。

### 9. 前端：直接复用，不用重写审批 UI

- **API**
  - `/approval-flow/get-by-form-type/{formType}`、`/approval-flow/form/options`
  - `/approval-resource/{push|revoke|simple-detail|detail|instance-detail}`
  - `/approval-action/{approve|reject|back|revoke|sign|batch-approve|batch-reject}`
  - `/approval-todo/*`
- **组件**：`frontend/packages/web/src/components/business/crm-approval/components/crm-approval-detail.vue` 有 `left` 插槽 —— 左边你的单据详情自己渲染，右边（审批链、记录、评论、同意/驳回）整体复用；列表页"审批中"状态用同目录下的 `crm-approval-popover.vue`。
- **必改**：
  - `frontend/packages/web/src/config/process.ts` 的 `businessTypeOptions` 加一项（否则管理员建不了流程）；待办点击跳转的目标路由要指到你的页面。
  - `crm-approval-detail.vue` 里的 `approvalStandardFormKeys`（硬编码 `QUOTATION/CONTRACT/ORDER/INVOICE` 的 Set），新标准表单不加进去，加签时你的 formType 会被当成 `customFormId` 传给加签接口。
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
2. 通知事件、日志模块、权限、显示名、待办资源名全是 switch 默认分支 —— 不补不报错，只表现成"待办列表里这行是空的"。注意其中 3 处不是默认分支而是硬校验（6.1 白名单、6.2 四层注册、6.3 通知配置），必须做。
3. `update_fields`（"改了哪些字段"）依赖 `@OperationLog` 的切面上下文，两个切面的 `@Order` 顺序别动。
4. 加签会给节点 id 加后缀：常量名是 `SIGN_SPILT`（代码里就是这个拼写）=`-SN`，加签任务的 `nodeId` 变成 `原节点ID-SN序号`。消费方统一 `split("-SN")[0]`（见 `ApprovalActionRequest.getNodeId`、`ApprovalReturnBackRequest`、`ApprovalInstanceService`）；自己写查询时也得先切，否则捞不到原节点配置。
5. 任务只做标记删除（行还在、状态清空），新写的统计别直接 `count(*)`。
6. 一切按 `node_round` 过滤，否则会捞到历史轮次的数据。
7. `update_fields`（“改了哪些字段”）在切面里取 `OperationLogContext`，拿不到时会打 `解析修改字段列表失败, logContext is null` 并让 `update_fields` 为空（只影响 `NOT_EQUAL_ORIGINAL` 这类条件）；`@OperationLog` 与 `@HitApproval` 的 `@Order` 顺序别动。
8. `/approval-resource/push` 不校验 resourceId 存在性：传空串会落一条 `resource_id=''` 的脏实例与待办，前端拿不到时先拦住。

## 建议推进顺序

先挑**一个**实体端到端打通：

```
建表 → handler → @HitApproval → 补注册点（6.1~6.4） → 种 module_form
     → 前端流程配置页能选到它并保存成功 → 新建/编辑/删除/驳回回滚全跑通
```

顺序有依赖：`module_form` 必须在前端配流程**之前**种好（见第 8 步），否则带条件节点的流程根本存不下去。

跑通后再复制。第二个实体的成本会降到"1 个 handler + 5 处 switch + 1 条前端选项"。

## 接入自检

改完后逐条跑一遍，你的 formType 应当出现在每一项的命中结果里（把 `PURCHASE` 换成你的枚举，6.4 的 `case` 也要一并换）：

```bash
# 6.1 白名单
rg "new ApprovalFormTypeEnum\[\]" -A 3 backend/crm/src/main/java/cn/cordys/crm/approval/service/ApprovalFlowService.java
# 6.2 四层注册
rg "PURCHASE" backend/crm/src/main/java/cn/cordys/crm/system/constants/FieldSourceType.java \
   backend/crm/src/main/java/cn/cordys/common/service/FieldSourceServiceProvider.java
# 6.3 通知三常量 + 一条数据
rg "PURCHASE_APPROVAL" backend/crm/src/main/java/cn/cordys/crm/system/constants/NotificationConstants.java backend/crm/src/main/resources/migration/
# 6.4 权限树 + formType 常量
rg "PURCHASE" backend/crm/src/main/resources/permission.json backend/crm/src/main/java/cn/cordys/common/constants/FormKeyConstants.java
# 6.4 switch 全覆盖：审批包内应命中 5 个文件
# ApprovalResourceService(3) / ApprovalActionService(2) / ApprovalTodoService(2)
# ApprovalCommentNoticeService(1) / ApprovalFlowService(1)
rg -c "case PURCHASE" --glob '*.java' backend/crm/src/main/java
# 穷举 switch 只能靠编译暴露
./mvnw -q -f backend/pom.xml -pl framework,crm -am compile -DskipTests
```

## 附：路线 C（松耦合版）

如果只是要"审批作为门槛、业务系统完全独立"（跨库或独立服务）：

1. 用一张自定义表单做"审批镜像单"，业务单据提审前把关键字段写进 `custom_form_data`。
2. 用该自定义表单建流程，审批通过后靠**节点后置配置**的 WebHook 回调你的 API。

代价：数据双写；WebHook 是 `@Async` 且无重试，最终态要自己再查 `/approval-resource/simple-detail/{resourceId}` 对账。
