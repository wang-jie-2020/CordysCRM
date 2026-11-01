# CordysCRM 审核模块设计

> 对应代码：`backend/crm/src/main/java/cn/cordys/crm/approval/**`
> 前端：`packages/web/src/views/system/process/**`（设计态）、`components/business/crm-approval/**`（运行态）
> 结论基于 2026-11 的代码阅读，接入自有实体前请以此为准再核对一遍。

## 一、这个模块解决什么

审批流 = **设计态的流程模板** + **运行态的实例/任务状态机** + **业务资源适配层**。

三块职责切得很开：

| 层 | 负责 | 不负责 |
|---|---|---|
| 配置层 | 哪个表单在什么时机（新建/编辑/删除）走哪个流程，节点顺序、条件分支、审批人怎么算 | 业务数据长什么样 |
| 运行层 | 实例、待办任务、轮次、流转、加签退回撤回、留痕 | 业务表的具体读法 |
| 适配层 | 回写业务表审批状态、编辑前快照与回退、审批后字段更新、WebHook | 流程本身怎么走 |

引擎内部只认两个字符串：`formType`（表单类型）和 `resourceId`（业务数据 ID）。所以**一个业务对象想接入审批，本质上就是提供一个 `formType` + 一套"怎么读我的名字/怎么回写我的状态"的实现**。

## 二、概念模型

### 2.1 设计态

```
审批流 approval_flow
  └── 版本 approval_flow_version     ← 改节点配置就新建一个版本
        ├── 节点 approval_node       START / APPROVER / CONDITION / DEFAULT / END
        │     ├── 审批人配置 approval_node_approver   （挂在 APPROVER 节点上）
        │     └── 条件配置 approval_node_condition    （挂在 CONDITION 节点上）
        └── 连线 approval_node_link  from → to（带 sort，决定分支尝试顺序）
```

两个容易忽略的设计：

- **版本是不可变的**。审批实例创建时锁住 `flow_version_id`，之后管理员怎么改流程，在途单据仍按老配置跑完。
- **节点带执行时机**（`execute_time`：CREATE / UPDATE / DELETE）。同一个流程里可以有"新建走 A 分支、编辑走 B 分支"两套起点。

### 2.2 运行态

```
审批实例 approval_instance   一个业务数据在某个时机的一次审批（可能有多轮历史）
  ├── 任务 approval_task     待办/已办的最小单位
  ├── 记录 approval_record   每一步的留痕（结果 + 意见）
  ├── 加签 approval_add_sign_task
  ├── 退回 approval_return_back_record
  ├── 附件 approval_instance_attachment（审批意见的附件）
  └── 评论 approval_comment(+_mention)   审批页右侧的讨论区
编辑前快照 approval_resource_snapshot   驳回/撤回时回滚业务数据用
```

**轮次（`node_round`）是这个模块的隐形主角**：同一个节点可能被走过多次（退回重走、重新提审）。任务和记录都带轮次，任何"按 nodeId 查任务"的新代码必须同时带轮次，否则会捞到历史轮次的数据。

**任务类型**：

| 类型 | 含义 |
|---|---|
| `NL` | 普通审批任务 |
| `CC` | 抄送（只读，不影响流转） |
| `SN` | 加签任务（同一节点内的一条链） |
| `BK` | 退回任务 |

**审批状态**（业务表 `approval_status` 列 / 实例 `approval_status`）：

`NONE`（无）→ `PENDING`（待提审）→ `APPROVING`（审批中）→ `APPROVED` / `UNAPPROVED`；另有 `REVOKED`（撤销）与自动态 `AUTO_APPROVED` / `AUTO_UNAPPROVED`。

## 三、数据模型要点

### 3.1 业务表必须提供的列

审批模块用**拼表名的原生 SQL** 回写业务数据（`ExtApprovalInstanceMapper.xml`），所以被审批的表必须有：

| 列 | 用途 |
|---|---|
| `id` | 主键 |
| `name` | 待办/消息/日志里显示的资源名 |
| `owner` | 评论通知找业务负责人 |
| `organization_id` | 多组织隔离 |
| `approval_status` | 审计状态回写、状态权限过滤 |
| `approved` | "是否审批通过过"，最终通过时置 1；编辑时机判定依赖它 |

表名映射在 `ApprovalResourceService.FORM_APPROVAL_TABLE`。

### 3.2 状态权限配置

`approval_flow.status_permissions`（JSON）描述**某个审批状态下允许哪些操作**（编辑/删除/导出/转移）。业务列表批量过滤时调用 `ApprovalFlowService.filterResourcesWithPermission(...)`。

规则：状态为 `NONE` 或空 → 不受限；配置了但没开 → 需要对应角色权限。

### 3.3 字段权限配置

`approval_node_approver.field_permissions`（JSON）是 `HIDDEN / VIEW / EDIT` 三元组，随实例详情通过 `currentNodeFieldPermissions` 下发前端，控制审批页表单的可编辑性。加签节点会被强制降级为全 `VIEW`。

## 四、主流程

### 4.1 触发：业务代码只加一行注解

```java
@HitApproval(formKey = FormKey.CONTRACT, executeType = ExecuteTimingEnum.UPDATE,
             resourceId = "{#request.id}", updateType = "{#request.updateType}",
             operatorId = "{#userId}", comment = "{#request.comment}")
```

切面 `HitApprovalAspect` 的行为按时机分三种：

| 时机 | 命中审批流时 | 未命中时 |
|---|---|---|
| CREATE | 业务数据先落库，状态置为 `PENDING`（待提审），等用户手动点"提交审批" | 正常执行 |
| UPDATE | 先存**编辑前快照** → 执行更新 → **事务提交后**自动提审（带"改了哪些字段"） | 正常执行 |
| DELETE | **不执行删除**，只建一个 DELETE 时机的实例 | 直接删 |

三个必须知道的规则：

1. **UPDATE 会被降级成 CREATE**：若该数据历史上从未审批通过过，则按"新建"处理（置 `PENDING` 等手动提审），而不是自动提审。
2. **提审必须在原事务提交之后、并且在新事务里执行**。原因写在代码注释里：`afterCommit` 回调时数据库连接仍绑在当前线程，普通事务会读到提交前的旧数据，导致条件分支判断错误。
3. `updateType == approval`（审批内部回写业务数据）直接放行，不重复触发审批。

DELETE 审批通过后，真正的删除通过 `executeDeleteAction` 调用业务处理器，并用 ThreadLocal 标记跳过审批检查，避免递归。

### 4.2 提审

```
命中流程 → 找 START 节点（按时机匹配）
   ├ 第一个节点是 END      → 直接通过（DELETE 时机顺手把数据删掉），发完结通知
   ├ 第一个节点是 EXCEPTION → 直接驳回 + 回滚快照，发完结通知
   └ 第一个节点是 APPROVER  → 业务表置 APPROVING，落实例和待办，发待办通知
```

已存在"审批中实例"时直接返回，不重复创建（防手动重复点提交）。

### 4.3 流转

每次审批通过后，从当前节点往后找下一个**真正要落地的节点**：

```
取连线的下一个节点（按 sort，取第一个条件成立的；都不成立走 DEFAULT）
  ├ CONDITION / DEFAULT → 条件节点不落地，继续往后跳
  ├ END                 → 走完，实例置 APPROVED
  ├ EXCEPTION           → 走挂，实例置 UNAPPROVED
  └ APPROVER            → 进入审批人解析（见第五章）
```

条件判断直接用**业务字段的当前值**（`ModuleFormService.compressResourceDetail`），所以"金额 > 10 万走总监审批"这类配置不需要业务代码配合。

### 4.4 动作

| 动作 | 语义 | 关键点 |
|---|---|---|
| 同意 | 当前任务置 `APPROVED`，判断节点是否整体通过 | 先锁实例行再处理任务（`FOR UPDATE`）+ 任务状态原子更新，防并发重复同意 |
| 驳回 | 任一审批人驳回即整单驳回 | **先回滚快照，再执行"驳回后动作"**，顺序反了会把后置动作写入的字段一起还原掉 |
| 加签 | 在当前节点插一条加签链 | 前加签 → 原审批人转 `PENDING` 等加签人；后加签 → 原任务直接 `APPROVED` |
| 退回 | 退回到指定历史节点重新审批 | 不结束流程，轮次 +1，已处理的节点留痕 |
| 撤回 | 审批人撤回自己刚才的同意 | 需要流程开启"允许撤回"，且只能撤自己的任务 |
| 批量同意/驳回 | 逐个复用单条逻辑 | 按 (实例 ID, 任务 ID) 固定顺序取锁，避免死锁 |

**节点"整体通过"的判定**：

- 或签（`ANY`）：有一个通过即通过；
- 会签（`ALL`）/ 依次（`SEQUENTIAL`）：该轮没有"审批中"的任务即通过。

节点通过后顺序做四件事：执行审批后动作（字段更新 + WebHook）→ 流转到下一节点 → 发本节点抄送 → 把本轮剩余"审批中"任务作废（假删除，保留历史）。

## 五、审批人怎么算

`resolveApprovers` 按 `approver_type` 分派，配合 `approver_direction`（自下而上 / 自上而下）和 `level`（第几级）：

| 类型 | 含义 |
|---|---|
| `MEMBER` | 指定成员 |
| `ROLE` | 指定角色 → 展开成角色下所有用户 |
| `SUPERIOR` / `MULTIPLE_SUPERIOR` | 直属上级 / 往上 N 级 |
| `DEPT_HEAD` / `MULTIPLE_DEPT_HEAD` | 部门负责人 / 往上 N 级部门负责人 |

算出候选审批人之后，还有四层"异常规则"依次生效：

1. **审批人为空** → 自动通过，或指定兜底审批人（审批管理员）。
2. **审批人 = 提交人** → 三种策略：允许 / 自动跳过 / 转交给提交人的直属上级（没有上级时，单人节点和或签节点直接跳过；会签和依次审批仍需逐人处理）。
3. **审批人重复出现**（同一个人在前面节点已经批过）→ 三种规则：仅首个节点审批（后续自动过）/ 连续同一人跳过 / 每个节点都要批。
4. **多人审批方式** → 会签（都要批）/ 或签（一个就够）/ 依次（按顺序一个个来）。

这四层规则的输出会写回节点响应的 `autoSkipUserIds`，建待办时据此直接把任务标成自动通过并补一条审批记录。

## 六、三种"权限"

| 名称 | 配置位置 | 生效方式 |
|---|---|---|
| 状态权限 | 流程主表 `status_permissions` | 业务列表批量过滤（编辑/删除/导出/转移） |
| 字段权限 | 节点 `field_permissions` | 随实例详情下发前端，控制审批页表单可编辑性 |
| 数据/查看权限 | 不配置，走角色权限 + 数据权限 | 参与过审批的人直接放行；其他人需业务模块的读权限 + 数据权限 |

## 七、扩展点

| 想扩展 | 扩展方式 |
|---|---|
| 新业务对象接入审批 | 实现 `ApprovalResourceHandler`（注册进 `FORM_SERVICE` 或自定义表单处理器） |
| 数据查看权限 | 实现 `ResourceAccessContextProvider`，按 `getFormType()` 匹配 |
| 条件判断读字段 | 在 `FieldSourceServiceProvider` 注册该 formType 的 `getSimple` / `getFieldValues` |
| 审批后动作 | 节点配置 `pass_post_config` / `reject_post_config`：字段更新 + WebHook（占位符递归替换，支持子表） |
| 新审批人类型 | `ApproverTypeEnum` + `ApprovalFlowService.resolveApprovers` |

`ApprovalResourceHandler` 的六个方法，按需实现但**不能半实现**：

```java
FormKey getFormKey();
void delete(resourceId, userId, orgId);                          // DELETE 审批通过后真正删除
void updateSnapshotApprovalStatus(param);                        // 业务快照表同步审批状态
void updateApprovalPostField(param);                             // 审批后字段更新
void revertToSnapshot(resourceId, userId, orgId, snapshotData);  // 驳回/撤回回滚
String getPreUpdateSnapshotData(resourceId, userId, orgId);      // 编辑前提快照
```

漏实现会**静默失效**：快照不实现 → 驳回后数据不回退；`delete` 不实现 → DELETE 审批通过后什么都没删。

## 八、已知约束与坑

1. **`reject()` 没有像 `approve()` 那样加锁和状态校验**。重复点驳回可能重复写记录、重复回滚快照。压测或做批量驳回前要留意。
2. **`update_fields` 依赖操作日志上下文**。切面从 `@OperationLog` 的上下文里算"改了哪些字段"，两个切面的顺序不能乱改，否则"字段变更"类条件会失效。
3. **加签会让 `current_node_id` 带后缀**（`SIGN_SPLIT`）。任何拿当前节点去查配置的代码都要先切掉后缀。
4. **任务的"删除"都是假删除**：状态清空，行还在，历史待办靠这个保留。新写的统计别直接 `count(*)`。
5. **流程配置和自定义表单共用一套"非标准 formType"语义**：`FormKey.ofKey(formType)` 返回 null 即视为自定义表单（formType 就是 `customFormId`）。自有实体接入时会天然落进这个分支，需要接管。
6. **通知映射写在 switch 里**：新表单类型如果不补 `sendFinishNotice` / `getLogModuleOfFormKey` 的分支，会落到"自定义表单"的默认事件上，而不会报错。
