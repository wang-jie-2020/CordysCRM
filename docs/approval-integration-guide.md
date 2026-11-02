# 审批接入指南（按实施顺序）

CordysCRM 的审批引擎对外只认两个字符串：`formType`（表单类型）和 `resourceId`（业务数据 ID）。

流程配置、实例/任务状态机、加签退回撤回、抄送、评论、状态权限、字段权限全都与业务无关；业务相关性只集中在"适配层"的 6 个方法和几处注册点。所以二次开发**不需要改引擎**，只需要把自有实体"注册"进去。

本文按"照做就能跑通"的顺序排列：**阶段一/二改库与代码 → 阶段三自检 → 阶段四跑通 → 阶段五前端**。每个阶段末尾有验证点，前一个没过别往下走。

---

## 阶段零：选路线

| 路线 | 做法 | 前后端 | 代码量 | 适用 |
| --- | --- | --- | --- | --- |
| A | 业务对象直接做成"自定义表单" | 复用 `/custom-form-data` | 0 行后端 | 字段全动态、能接受 EAV 表结构 |
| **B（推荐）** | 自有表 + 新 `FormKey` 深度接入 | 自己的 API，复用审批相关 API | 1 个 handler + 1 个 provider + 十几处注册 | 同库、要强 schema、要状态回写/回滚 |
| C | 自有表 + "审批镜像单" + WebHook 回调 | 完全独立服务 | 最松耦合，但要数据双写 | 跨库/微服务，审批只当"闸门" |

数据结构是常规强类型表、前端是常规 API → 选 B，往下看。

先挑**一个**实体端到端打通，别并行铺开。第二个实体的成本会降到第一个的 1/3。

---

# 阶段一：数据与配置（只改库，不动代码）

## 1. 建表：业务表必需的 6 个列

引擎用 `${sourceTable}` 拼原生 SQL 直接回写（`ExtApprovalInstanceMapper.xml`），列名是硬约定：

| 列 | 用途 |
| --- | --- |
| `id` | 主键 |
| `name` | 待办/消息/日志里显示的资源名 |
| `owner` | 评论通知找业务负责人 + 数据权限 |
| `organization_id` | 多组织隔离 |
| `approval_status` | 审批状态回写、状态权限过滤 |
| `approved` | 历史上是否审批通过过；`UPDATE` 降级判定依赖它 |

新表默认值：`approval_status` 用 `NONE`、`approved` 用 `0`。

两个注意点：

- **存量表补列要回填**：`UPDATE t SET approved = 1 WHERE approval_status = 'APPROVED'`（参考 `V1.7.2_2_1__data.sql`）。不回填的老数据会被当成"从未审批通过过"，编辑时走 CREATE 降级。
- **`owner` 必须有值**：评论通知靠它找业务负责人（`ApprovalCommentNoticeService`），为空收不到评论提醒。

## 2. 权限：权限树 + 常量 + 角色种子

三处要一起加，缺一处就是"权限下拉里没有你的模块"：

| 位置 | 加什么 |
| --- | --- |
| `backend/crm/src/main/resources/permission.json` | 权限树节点。二级节点的 `id` 必须是 formType 对应的大写名（如 `PURCHASE`），因为 `ApprovalFormTypeEnum.permissionId` 要指向它 |
| `common/constants/PermissionConstants.java` | `PURCHASE_READ/ADD/UPDATE/DELETE` 常量 |
| 迁移 dml | `sys_role_permission` 给角色种种子（至少 `org_admin` 全给） |

`ApprovalFormTypeEnum.permissionId` 与 `permission.json` 节点 `id` 不一致时，审批页的权限下拉是空的。

## 3. 模块表单字段配置（条件、后置字段的硬前提）

条件分支、后置字段更新、字段权限都读 `moduleFormCacheService.getBusinessFormConfig(formType, orgId)`，前端对应 `getDatasourceFieldConfig(formType)`。

你的 formType 必须在 `sys_module_form` + `sys_module_form_blob` + `sys_module_field*` 里有完整一份字段配置。

**没有它不是"少点功能"，而是流程存不进去**：只要任一节点配置里出现条件节点或后置字段，保存流程就走 `UserViewService.buildOptionMap` → `ModuleFormService.getModuleFormByKey`，查不到直接抛 404「资源不存在」，报错完全指不到这一层。

种配置两条路：

- 走前端表单设计器（真实模块的正路）
- 纯后端验证：拷一份现有模块的四个表行，改 `form_key` 和主键。注意 MySQL 里 `varchar` 主键与数字字面量比较会因隐式转换误匹配多行，**务必加引号**

**字段 id 就是取数 DTO 的属性名**：`ModuleFormService.compressResourceDetail` 把业务详情 DTO 转成 Map，key 当 fieldId 用。所以条件里写 `amount`，DTO 里就得有 `amount` 属性。

> 验证点：`GET /module/form/config/{formType}` 返回 200 且字段齐全。

---

# 阶段二：后端接入（改代码）

## 4. 注册 formType（3 个枚举/常量）

| 文件 | 加什么 |
| --- | --- |
| `common/constants/FormKey.java` | `PURCHASE("purchase")` |
| `common/constants/FormKeyConstants.java` | `PURCHASE = "purchase"`（`@CsPermission(formType = ...)` 用） |
| `approval/constants/ApprovalFormTypeEnum.java` | `PURCHASE("PUR-APV", "PURCHASE", "purchase")`，第二个参数是阶段一第 2 步的权限树节点 id |

`FormKey` 必须加：`FormKey.ofKey(formType) == null` 会被引擎当成"自定义表单"（formType 当作 customFormId），走错分支。

## 5. 表名映射

`ApprovalResourceService.FORM_APPROVAL_TABLE` 的 `static {}` 里加一行：

```java
FORM_APPROVAL_TABLE.put(FormKey.PURCHASE.getKey(), "purchase");
```

`static` HashMap，只能改代码，没有配置入口。

## 6. 业务 service 提供三个取数方法

```
getSimple(String id)                        → 详情 DTO（属性名即字段 id）
batchGetSimpleByIds(List<String> ids)       → 详情 DTO 列表
getFieldValues(String id)                   → 引用字段展示用，一般与 getSimple 同源
```

## 7. 实现 `ApprovalResourceHandler`（6 个方法）

实现类标 `@Service` 即自动注册（`ApprovalResourceService` 在 `@PostConstruct` 里按 `getFormKey()` 收进 `FORM_SERVICE`）。

```java
FormKey getFormKey();                                     // 返回 FormKey.PURCHASE
void delete(id, userId, orgId);                           // DELETE 审批通过后真删（引擎已用 ThreadLocal 跳过递归）
void updateSnapshotApprovalStatus(param);                 // 有快照表才需要，没有就空实现
void updateApprovalPostField(param);                      // 审批后置字段更新
void revertToSnapshot(id, userId, orgId, snapshotData);    // 驳回/撤回回滚
String getPreUpdateSnapshotData(id, userId, orgId);        // 编辑前快照，返回更新请求 DTO 形态的 JSON
```

**六个方法必须全实现，漏了是静默失效。** 三个实现要点：

- `delete()` 就是你的业务删除方法本身（引擎在 DELETE 审批通过后调它），**别在它里面再挂 `@HitApproval`**。
- `getPreUpdateSnapshotData()` 返回的是**更新请求 DTO 的 JSON**，不是实体 JSON；里面要把 `updateType=approval` 带上，`revertToSnapshot()` 解析后原样调 update 才不会二次提审。
- `revertToSnapshot()` 内部调 update 必须走**代理**（`CommonBeanFactory.getBean(...)`），直接 `this.update()` 会绕过切面。
- `getFormKey()` 返回 null 的 handler 只会占一个槽位（`CUSTOM_FORM_SERVICE`），两个 null 会互相覆盖。
- 有快照表的模块（合同/报价/订单/发票）才需要 `FormKey.hasSnapshot()` 和 `updateSnapshotApprovalStatus`。

## 8. 挂 `@HitApproval`（三个时机）

```java
@HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.CREATE,
             operatorId = "{#userId}")

@HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.UPDATE,
             resourceId = "{#request.id}", updateType = "{#request.updateType}",
             operatorId = "{#userId}", comment = "{#request.comment}")

@HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.DELETE,
             resourceId = "{#id}", operatorId = "{#userId}")
```

- `formKey` 有默认值 `FormKey.ORDER`，写漏了不报错、静默按订单走 —— 一定要显式写。
- 自定义表单用 `formKeyExpr = "{#request.customFormId}"` 代替 `formKey`。
- DELETE 通常单独包一层 `deleteWithApprovalCheck()`（挂注解）→ 代理调 `delete()`，否则自调用绕过切面。

同时业务代码要按下面的时序契约写，写错了表现是"审批状态不回来"或"回滚不生效"：

| 时机 | 引擎行为 | 业务侧要做的 |
| --- | --- | --- |
| CREATE | 落库后置 `PENDING`，等用户手动提审 | 新数据默认 `approval_status=NONE`；提供"提交审批"按钮调 `/approval-resource/push` |
| UPDATE | 先存编辑前快照 → 执行更新 → **事务提交后**自动提审 | `getPreUpdateSnapshotData` 返回更新请求形态的 JSON；update 里保留原 `approval_status`/`approved` 不被请求覆盖 |
| DELETE | 不删，只建一个 DELETE 实例 | `delete()` 里真删，别在里面再挂 `@HitApproval` |
| 驳回/撤回 | 回调 `revertToSnapshot` | 内部调更新时 `updateType="approval"`，否则又触发一次审批 |
| 审批通过 | 引擎直接写你的表的 `approval_status` / `approved` | 业务代码里不要再重复维护这两个字段 |

两条隐含规则：

1. `UPDATE` 时若该数据历史上从未审批通过过（`approved=0`），引擎自动降级成 `CREATE`（置 `PENDING` 等手动提审）。
2. 提审必须在原事务提交后、且在**新事务**里执行（`pushAfterCommit`）。原因：`afterCommit` 时连接仍绑在当前线程，普通事务会读到提交前的旧数据，导致条件分支判错。

## 9. `validateFormType` 白名单（不补：建流程直接失败）

`ApprovalFlowService.validateFormType` 用**硬编码枚举数组**放行标准表单，不走 `ApprovalFormTypeEnum` 遍历，所以第 4 步加了枚举**不够**：

```java
for (ApprovalFormTypeEnum type : new ApprovalFormTypeEnum[] {
        CLUE, CUSTOMER, OPPORTUNITY, QUOTATION, CONTRACT, INVOICE, ORDER }) { ... }
```

不加进去 → `POST /approval-flow` 会把你的 formType 当自定义表单 ID 去库里查，查不到抛 `module.form.illegal`。报错完全指不到这里，是最费时的一处。

## 10. `FieldSourceServiceProvider` 四层注册（不补：条件永远判空）

从上到下四层，缺任一层条件分支都判空：

1. `crm/system/constants/FieldSourceType.java` 加枚举（值 = 业务表名）
2. `sources.put(FieldSourceType.PURCHASE, new SourceReader(purchaseService::getSimple, purchaseService::batchGetSimpleByIds))`
3. 业务 service 的三个取数方法（第 6 步）
4. 最后才是 `approvalSources.put(FormKey.PURCHASE.getKey(), sources.get(FieldSourceType.PURCHASE))` + `approvalFields.put(..., purchaseService::getFieldValues)`

条件分支判断和审批后动作都靠它取业务字段（`ModuleFormService.compressResourceDetail`）。

## 11. 审批结果通知（3 个常量 + i18n + 1 条数据）

`ApprovalActionService.sendFinishNotice` 的 switch 只是第一步，缺下面任一项通知就静默不发（发送方 catch 掉异常，无日志）：

1. `NotificationConstants.Module` 加 module（= `sys_message_task.task_type`）
2. `NotificationConstants.Event` 加事件
3. `NotificationConstants.TemplateText` 加 `Xxx_TEXT` 字段 + i18n `message.xxx` 模板正文（`MessageTemplateUtils` 按 `{event}_TEXT` 取）
4. 迁移 dml 往 `sys_message_task` 种一行 `(event, task_type, sys_enable = 1)` —— `MessageDetailService` 按 `(module, event)` 查这张表，没配置直接不发

## 12. 其余注册点（不补不报错，只静默降级）

| 文件 | 位置 |
| --- | --- |
| `approval/service/ApprovalResourceService.java` | `getLogModuleOfFormKey`、`getReadPermissionByFormType`、`getFormKeyDisplayName` |
| `approval/service/ApprovalFlowService.java` | `getFlowFormDisplayName` |
| `approval/service/ApprovalActionService.java` | `sendFinishNotice` 的事件 switch + **`getLogModuleOfFormKey`（私有，另有一份实现）** |
| `approval/service/ApprovalCommentNoticeService.java` | 评论通知的 `resourceType` switch（`default` 会静默落到 `Module.APPROVAL`） |
| `approval/service/ApprovalTodoService.java` | `loadResourceNameMap`、按资源名搜索的 type 别名、名称搜索 |
| `framework/.../aspectj/constants/LogModule.java` | 日志模块常量（`getLogModuleOfFormKey` 的返回值） |

`getLogModuleOfFormKey` 有两份：`ApprovalResourceService`（public）和 `ApprovalActionService`（private），只补一处会漏日志模块。

`FormKey` 枚举一加，**两处穷举 switch 会直接编译失败**（`McpFormulaController`、`StandardRelatedDetailTabDataHandler`）——这反而是好事，`mvn compile` 会替你揪出来。其余非穷举的地方仍会静静降级。

## 13. 数据权限

实现 `cn.cordys.common.permission.ResourceAccessContextProvider`，三个方法都要（`batchGetOwnerIds` 是抽象方法不是 default，少写编译不过）：

- `getFormType()` —— 返回 formType 字符串
- `getAccessContext(resourceId, orgId)` —— 返回 `ownerId` + `approvalStatus`，资源不存在返回 `null`
- `batchGetOwnerIds(resourceIds, orgId)` —— 批量查负责人，返回 `资源id → owner` 映射

组织共享、没有负责人的资源重写 `requiresOwner()` 返回 `false`。

审批详情页的查看鉴权走它：参与过审批的人（提审人/审批人/抄送人）直接放行；其他人需要"模块读权限 + 数据权限"。

---

# 阶段三：自检

```bash
# 9. 白名单
rg "new ApprovalFormTypeEnum\[\]" -A 3 backend/crm/src/main/java/cn/cordys/crm/approval/service/ApprovalFlowService.java
# 10. 四层注册
rg "PURCHASE" backend/crm/src/main/java/cn/cordys/crm/system/constants/FieldSourceType.java \
   backend/crm/src/main/java/cn/cordys/common/service/FieldSourceServiceProvider.java
# 11. 通知三常量 + 一条数据
rg "PURCHASE_APPROVAL" backend/crm/src/main/java/cn/cordys/crm/system/constants/NotificationConstants.java backend/crm/src/main/resources/migration/
# 2/4. 权限树 + formType 常量
rg "PURCHASE" backend/crm/src/main/resources/permission.json backend/crm/src/main/java/cn/cordys/common/constants/FormKeyConstants.java
# 12. switch 全覆盖：审批包内应命中 5 个文件
# ApprovalResourceService(3) / ApprovalActionService(2) / ApprovalTodoService(2)
# ApprovalCommentNoticeService(1) / ApprovalFlowService(1)
rg -c "case PURCHASE" --glob '*.java' backend/crm/src/main/java
# 穷举 switch 只能靠编译暴露
./mvnw -q -f backend/pom.xml -pl framework,crm -am compile -DskipTests
```

每一条的命中结果里都该出现你的 formType，缺哪条回上面补。

---

# 阶段四：跑通验收

后端不依赖前端就能验收：流程可以用 `POST /approval-flow` 直接建。按顺序过一遍，全绿才算接入完成。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `POST /approval-flow`（`formType` = 你的类型） | 200；`module.form.illegal` = 第 9 步没做 |
| 2 | 新建业务数据 | 落库后 `approval_status=NONE`（返回体里可能还是 NONE，引擎在方法返回后才回写） |
| 3 | 命中 CREATE 流程后查库 | `PENDING` |
| 4 | `POST /approval-resource/push` | `APPROVING` + 出现待办任务 |
| 5 | `POST /approval-action/approve` | `APPROVED`、`approved=1` |
| 6 | 编辑该数据 | 编辑前快照落库；事务提交后自动建 `execute_time=UPDATE` 实例 |
| 7 | `POST /approval-action/reject` | 业务数据回滚成编辑前、快照清理、状态 `UNAPPROVED` |
| 8 | `POST /approval-resource/revoke` | `REVOKED` |
| 9 | 删除 | 行还在、`APPROVING`、实例 `execute_time=DELETE` |
| 10 | 同意该删除实例 | 业务行真删 |
| 11 | 带条件节点（如 `amount >= 1000`）各提审一条高/低值 | 高值走审批节点、低值走 DEFAULT 自动通过 —— 这条过了才说明第 10 步真的生效 |
| 12 | 查 `sys_notification` | 有你的 `{FORM}_APPROVAL` 记录 |
| 13 | `GET /approval-todo/pending/count` | 返回 `{"purchase": n}` |
| 14 | `GET /approval-todo/processed/page` | `resourceName` 非空 |

---

# 阶段五：前端

不接前端也能跑通后端，但真实业务一定要接。**全部复用现有框架，不要自己写一套审批 UI**。

先记住一个硬约束：`ApprovalFieldEditor` 用的是通用表单组件，它对详情接口的形状有要求（见 16.1），形状不对会渲染空白。

## 15. 前端注册点

加 `FormDesignKeyEnum` 值会触发 **4 个穷举 `Record`** 的 TS 报错，这是好事：按报错逐个补就行。

| 顺序 | 文件 | 加什么 |
| --- | --- | --- |
| 1 | `lib-shared/enums/formDesignEnum.ts` | `PURCHASE = 'purchase'`，值必须与后端 `FormKey.getKey()` 一致 |
| 2 | `components/business/crm-form-create/config.ts` | 4 个穷举表：`getFormConfigApiMap`、`createFormApi`、`updateFormApi`、`getFormDetailApiMap`，一个都不能漏 |
| 3 | `config/process.ts` | `businessTypeOptions` 加一项，否则管理员在流程设计器里选不到 |
| 4 | `components/business/crm-approval/components/crm-approval-detail.vue` | `approvalStandardFormKeys` 加你的 key，否则加签时 formType 被当 `customFormId` 传 |
| 5 | `enums/routeEnum.ts` + `router/routes/modules/xxx.ts` | 菜单路由，`meta.permissions` 用你的读权限；`routes/modules` 是自动 glob，新建文件即生效 |
| 6 | `views/<模块>/locale/{zh-CN,en-US}.ts` | `module.purchase`、`formDesignKey` 相关文案 |
| 7 | `views/system/process/.../useConditionFilterConfig.ts` | `formTypeConfigMap`（可选，只影响条件面板的系统字段） |

## 16. API 层三件套

按现有模块的目录约定（以 order 为模板）：

| 文件 | 内容 |
| --- | --- |
| `lib-shared/api/requrls/purchase.ts` | URL 常量 |
| `lib-shared/api/modules/purchase.ts` | `useXxxApi(CDR)`，返回列表/详情/新增/更新/删除/表单配置 |
| `web/src/api/modules/index.ts` | `const purchaseApi = usePurchaseApi(CDR)` + 解构导出 |
| `lib-shared/models/purchase.ts` | `PurchaseItem` / `SaveXxxParams` |

### 16.1 列表与详情接口的最小形状

- 列表：`{ current, pageSize, list, total }`（`CommonList<T>`）
- 详情：**必须是 `FormDetail` 形状** —— `{ moduleFields: [], ...业务字段平铺 }`。少 `moduleFields` TS 会报错，业务字段名必须与 `sys_module_form` 里的 `businessKey` 对得上
- 新增/更新：前端会把 `{ ...表单值, moduleFields, updateType, approvalTaskId }` 整个提交；后端 DTO 忽略多余字段即可

### 16.2 权限

菜单与按钮走登录返回的 `permissionIds`；`admin` 用户前端 `isAdmin` 直接放行、后端 `PermissionUtils` 也直通，所以本地用 admin 测会看不到"无权限"问题。

## 17. 详情页：抄 `views/order/order/components/detail.vue`

结构就三块，都是现成组件：

```vue
<CrmApprovalDetail
  :form-key="FormDesignKeyEnum.PURCHASE"
  :source-id="sourceId"
  :approval-status="detailInfo?.approvalStatus"
  left-loading
  @saveApproval="..."
  @delete-approved="..."
>
  <template #left="{ fieldPermissions, taskNode, setLoading, setEditing }">
    <ApprovalFieldEditor
      :form-key="FormDesignKeyEnum.PURCHASE"
      :source-id="sourceId"
      :field-permissions="fieldPermissions"
      :task-node="taskNode"
      :set-loading="setLoading"
      :set-editing="setEditing"
      @init="..."
      @saved="..."
    />
  </template>
</CrmApprovalDetail>
```

- 右侧（审批链、记录、评论、同意/驳回）全在 `crm-approval-detail.vue` 里，不用管。
- 左侧插槽直接上 `components/business/crm-approval/components/approvalFieldEditor.vue`，它已经把详情/编辑切换、字段权限、审批态保存（`updateType: 'approval'`）都包好了。
- **插槽就是四个参数**：`fieldPermissions`（引擎下发的字段权限）、`taskNode`（当前待办节点）、`setLoading`、`setEditing`。插槽参数全部原样透传给 `ApprovalFieldEditor` 即可，不要自己计算。
- 有快照表的模块才需要 `description-form-key`（如 `ORDER_SNAPSHOT`）。
- `@saveApproval(callback)` 的用法：`await callback()` 后刷新详情，别自己发保存请求。
- 列表页"审批中"状态用同目录的 `crm-approval-popover.vue`；新建表单直接复用 `CrmFormCreateDrawer`（`:form-key` + `:source-id`）。

## 18. 路由与入口

- "提交审批"按钮调 `/approval-resource/push`（`{ resourceId, formKey }`），前端已有封装 `reviewResource()`。
- 待办列表点击跳转的目标路由要指到你的详情页。
- 其余审批 API 全部复用：`/approval-flow/*`、`/approval-resource/*`、`/approval-action/*`、`/approval-todo/*`。

> 验证点：`pnpm --filter @cordys/web type:check` 与 `npm run lint`（在 `packages/web` 下）都干净，vite 对新增模块返回 200 无 transform error。

---

# 附录 A：已知坑（按遇到概率排序）

1. `FORM_APPROVAL_TABLE` 是 `static` HashMap，加 formType 只能改代码，无配置入口。
2. 通知事件、日志模块、权限、显示名、待办资源名大多是 switch 默认分支 —— 不补不报错，只表现成"待办列表里这行是空的"。但 3 处不是默认分支而是硬校验（第 9 步白名单、第 10 步四层注册、第 11 步通知配置），必须做。
3. `update_fields`（"改了哪些字段"）依赖 `@OperationLog` 的切面上下文。取不到时只打一行 `解析修改字段列表失败, logContext is null`，`update_fields` 为空（影响 `NOT_EQUAL_ORIGINAL` 这类条件），主流程不受影响。`@OperationLog` 与 `@HitApproval` 的 `@Order` 顺序别动。
4. 加签会给节点 id 加后缀：常量名是 `SIGN_SPILT`（代码里就是这个拼写）= `-SN`，加签任务的 `nodeId` 变成 `原节点ID-SN序号`。消费方统一 `split("-SN")[0]`（见 `ApprovalActionRequest.getNodeId`、`ApprovalReturnBackRequest`、`ApprovalInstanceService`）；自己写查询时也要先切，否则捞不到原节点配置。
5. 任务只做标记删除（行还在、状态清空），新写的统计别直接 `count(*)`。
6. 一切按 `node_round` 过滤，否则会捞到历史轮次的数据。
7. `/approval-resource/push` 不校验 resourceId 存在性：传空串会落一条 `resource_id=''` 的脏实例和待办，前端拿不到 id 时先拦住。
8. `sendFinishNotice` 之后还会异步发 WebHook，没配 WebHook 时每次审批结束报一条 `异步任务异常: sendWebHook`（已 catch，不影响主流程），日志里会一直有噪音。

# 附录 B：路线 C（松耦合版）

如果只是要"审批作为门槛、业务系统完全独立"（跨库或独立服务）：

1. 用一张自定义表单做"审批镜像单"，业务单据提审前把关键字段写进 `custom_form_data`。
2. 用该自定义表单建流程，审批通过后靠**节点后置配置**的 WebHook 回调你的 API。

代价：数据双写；WebHook 是 `@Async` 且无重试，最终态要自己再查 `/approval-resource/simple-detail/{resourceId}` 对账。

# 附录 C：已接入实体与成本参考

| 实体 | formType | 快照表 | 备注 |
| --- | --- | --- | --- |
| 线索/客户/商机/报价单/合同/发票/订单 | `clue`…`order` | 报价单/合同/发票/订单有 | 全量接入，可当模板 |
| 自定义表单 | 具体 `customFormId` | 无 | `FormKey.ofKey` 返回 null 的分支 |
| 采购单（验证用） | `purchase` | 无 | 最小可跑样例，前后端全链，见 `crm/purchase/`、迁移 `1.9.6`、`views/purchase/` |

第一个实体的实际工作量（`purchase` 实测）：

| 部分 | 文件数 | 说明 |
| --- | --- | --- |
| 迁移 | 2 | 建表 + 权限/消息任务 dml |
| 后端 | 7 | domain、3 个 DTO、service、controller、provider |
| 后端注册点 | ~15 处 | 见阶段二清单 |
| 前端 | 12 | 4 个穷举 Record、路由、页面、API 层三件套 |

第二个实体复制时只改业务部分，注册点照清单过一遍即可。
