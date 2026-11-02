# 从 0 到 1 接入一个业务实体到审批引擎

> 读者：第一次给 CordysCRM 加"可审批模块"的后端/全栈工程师。
> 前置：能跑起本项目（MySQL + Redis + 后端 8081 + 前端 5173），会读 Java/Spring 和 Vue。
>
> 本文是**叙述式**的：每一步都说明"为什么这么做""不这么做会怎样""怎么确认做对了"。
> 具体改哪个文件、加哪一行，查 [approval-integration-guide.md](./approval-integration-guide.md)（清单式，按阶段编号）。两份配合看：先读本文建立整体判断，动手时对着清单勾。

本文所有结论都来自一次真实接入（`purchase` 采购单，前后端全链跑通），不是设计推演。

---

## 一、先建立心智模型：为什么这件事"步骤多但都不难"

审批引擎对外只有两个入参：

```
formType     字符串，标识"这是哪种业务单据"
resourceId   字符串，标识"是哪一条"
```

流程配置、状态机、加签/退回/撤回、抄送、评论、字段权限、状态权限，全部与业务无关，都建立在 `(formType, resourceId)` 之上。`resourceId` 只是字符串，引擎不需要认识你的表。

那为什么接入时要改十几处？因为引擎**没有插件发现机制**。它不扫描 "谁实现了审批接口"，而是用三种硬编码方式分派：

| 机制 | 例子 | 加新类型时的表现 |
| --- | --- | --- |
| 枚举 | `FormKey`、`ApprovalFormTypeEnum`、`FieldSourceType` | 不加枚举：被当成"自定义表单"，走错分支 |
| `static` Map | `FORM_APPROVAL_TABLE`、`FORM_SERVICE`、`approvalSources` | 不加：查不到 → 返回 null → 静默降级 |
| `switch` / 穷举数组 | 显示名、日志模块、通知事件、待办资源名、权限 | 不加：走 `default` → 空字符串/空列表 |

所以**"注册点很多"是这个架构的固有代价，不是设计缺陷**。理解这一点之后，心态就对了：你要做的不是"写业务逻辑"，而是"把你的类型登记进引擎的每一张表里"。

只有两处是真逻辑，必须自己写：

1. `ApprovalResourceHandler` —— 引擎回调你的 6 个方法（删数据、存快照、回滚）
2. `ResourceAccessContextProvider` —— 告诉引擎"这条数据的负责人是谁、审批状态是什么"

其余全是登记。

---

## 二、开工前必须做的四个决策

### 决策 1：选哪条路线（A / B / C）

| 路线 | 一句话 | 什么时候选 |
| --- | --- | --- |
| A 自定义表单 | 业务对象直接做成"自定义表单"，复用 `/custom-form-data` | 字段全动态、能接受 EAV（`custom_form_data` + 子表）存储、不需要强 schema |
| **B 自有表**（默认） | 自己的表 + 新 `FormKey`，自己的 CRUD，复用审批 API | 数据结构是常规强类型表、已有或即将有自己的列表/详情页 |
| C 镜像单 + WebHook | 一张自定义表单当"审批镜像单"，通过后回调你的服务 | 跨库 / 微服务 / 审批只想当一道闸门 |

**判断口径**：如果你的业务表已经有（或必然会有）独立的列、索引、外键、报表需求 → 选 B。如果只是"临时收个审批意见"、字段随时会变 → 选 A。只有在系统边界真的隔着网络时，才值得付 C 的代价（数据双写 + WebHook 无重试，要自己对账）。

选 B 之后，本文余下部分才适用。

### 决策 2：第一个实体挑哪个

**挑"最小但真实"的那个，不要一上来就挑合同/订单。**

理由：接入过程有 15+ 个登记点，每个登记点都对应一个"忘了会静默失效"的失败模式。用一个简单实体跑通，你建立的是"清单肌肉记忆"；用复杂实体，你会同时被快照、子表、公式字段、状态流转干扰，分不清是接入错了还是业务逻辑错了。

`purchase` 之所以合适：4 个业务字段（name/owner/amount + id）、无自定义字段、无快照表、无子表。

### 决策 3：先做后端还是先做前端

**先后端，且用 curl 验收完再动前端。**

理由：流程可以完全用 API 建（`POST /approval-flow`），前端只是"把审批 UI 摆到页面上"。如果先后端后前端一起调，出问题时你无法判断是引擎没接上还是页面传错了参数。后端 14 条验收全绿之后，前端基本只需要"接线"。

### 决策 4（容易被忽略）：要不要快照表和条件分支

| 能力 | 额外成本 | 何时必须要 |
| --- | --- | --- |
| 编辑后可回滚（驳回还原） | `getPreUpdateSnapshotData` + `revertToSnapshot` 两个方法 | 审批期间允许编辑，且驳回要撤销改动 |
| 审批后置字段更新 | `updateApprovalPostField` 写实 + `sys_module_form` 字段配置 | 流程里要配"通过后把某字段改成某值" |
| 条件分支 | `sys_module_form` 字段配置 + `FieldSourceServiceProvider` 四层注册 | 流程要按金额/部门/负责人分流 |
| 自定义字段（EAV 子表） | 自己的 `xxx_field` 表 + `getFieldValues` | 业务需要用户自定义字段 |

**这些不是"以后再加"的，是建表前就要定的**：有条件分支就必须有 `module_form` 配置；有快照表才需要 `FormKey.hasSnapshot()` 返回 true。先定，能省掉返工。

---

## 三、第 1 步：把库准备好（约 1 小时）

### 3.1 建表：那 6 个列不是建议，是接口

```sql
CREATE TABLE purchase (
    id              VARCHAR(32) NOT NULL,
    name            VARCHAR(255) NOT NULL,          -- 待办/通知/日志里显示的名字
    owner           VARCHAR(32) NOT NULL,           -- 负责人：评论通知 + 数据权限
    approval_status VARCHAR(50) NOT NULL DEFAULT 'NONE',
    approved        TINYINT(1) DEFAULT 0,           -- 历史上是否审批通过过
    organization_id VARCHAR(32) NOT NULL,
    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
    create_user VARCHAR(32) NOT NULL, update_user VARCHAR(32) NOT NULL,
    PRIMARY KEY (id)
);
```

**为什么是这 6 个**：引擎不做 ORM，它用 `${sourceTable}` 拼原生 SQL 直接回写你的表（`ExtApprovalInstanceMapper.xml`）。列名写错 = 审批状态写不进你的表，而且不报错（SQL 更新 0 行）。

`approved` 这个列名字很别扭，但它是"编辑时是否自动提审"的开关：历史上从未通过过（`approved=0`）的单据被编辑时，引擎会降级成"重新提交审批"而不是"走变更审批"。**存量表补列后必须回填**：

```sql
UPDATE t SET approved = 1 WHERE approval_status = 'APPROVED';
```

不回填的表现：老数据编辑一下，突然要求重新审批，用户会认为是 bug。

### 3.2 模块表单配置：这不是"可选美化"，是硬前提

这一条是本次实践里最容易栽的坑，原文写作"不做也能跑"是错的。

`sys_module_form` / `sys_module_form_blob` / `sys_module_field` / `sys_module_field_blob` 四张表里要有你这个 formType 的字段配置。没有的话：

- **只要流程里出现条件节点或后置字段，保存流程就直接抛 404「资源不存在」**（`UserViewService.buildOptionMap` → `ModuleFormService.getModuleFormByKey`），报错完全指不到这一层
- 审批页左侧表单渲染不出字段
- 流程设计器的字段权限、条件字段列表是空的

**怎么种**：

- 正路：前端表单设计器里配一遍（真实模块应该这么做）
- 验证用：拷一份现有模块的四个表行，改 `form_key` 和主键。手工 SQL 时注意 MySQL 的 `varchar` 主键与数字字面量比较会因隐式转换同时命中多行 —— **SQL 里给 id 加引号**，我在这上面浪费了十几分钟。
- 拷完之后可以用 `POST /module/form/save` 覆盖成干净字段配置（该接口要求行已存在，所以要先拷）

**字段 id 约定**：`ModuleFormService.compressResourceDetail` 把你的详情 DTO 转成 Map，**key 当作 fieldId**。所以条件里写 `amount`，DTO 里就必须有 `amount` 属性。业务主表字段用 `businessKey` 绑定（`name` / `owner` / `amount`）。

### 3.3 权限：三处一起加

| 位置 | 作用 | 漏了的表现 |
| --- | --- | --- |
| `permission.json` | 权限树定义，二级节点 id 必须等于 `ApprovalFormTypeEnum.permissionId` | 审批页的权限下拉为空 |
| `PermissionConstants` | `PURCHASE:READ/ADD/UPDATE/DELETE` 常量 | 编译不过（引用了不存在的常量） |
| 迁移 dml → `sys_role_permission` | 给角色授权 | 非 admin 用户看不到菜单 |

后台 admin 是特例：`PermissionUtils.hasPermission` 对 admin 直接返回 true，前端 `userStore.isAdmin` 也直接放行。**所以本地用 admin 测，永远看不到权限问题** —— 这是"看起来没问题、上线才发现"的典型来源，要专门造一个普通用户验一次。

---

## 四、第 2 步：后端接入（约 1 天）

按依赖顺序，不要跳。每一步后面括号里是"跳过会怎样"。

### 4.1 注册类型（`FormKey` / `FormKeyConstants` / `ApprovalFormTypeEnum`）

`FormKey.ofKey(formType)` 返回 null 时，引擎认为 formType 是自定义表单 ID，会去查 `custom_form_data`。（跳过：你的数据被当自定义表单处理，全线错位）

`FormKeyConstants` 是给 `@CsPermission(formType = ...)` 用的字符串常量，和枚举重复但两套都在用。

### 4.2 表名映射

```java
static { FORM_APPROVAL_TABLE.put(FormKey.PURCHASE.getKey(), "purchase"); }
```

引擎靠它拼 `update purchase set approval_status = ? where id = ?`。（跳过：所有回写 SQL 都被跳过，审批状态永远不落库）

### 4.3 三行取数方法

`getSimple(id)` / `batchGetSimpleByIds(ids)` / `getFieldValues(id)`。它们服务于两件事：条件求值和引用字段展示。返回的 DTO **属性名就是字段 id**（见 3.2）。

### 4.4 实现 `ApprovalResourceHandler`

六个方法，标 `@Service` 自动注册（按 `getFormKey()` 落 `FORM_SERVICE`）。真正的逻辑只有三处：

```java
// 1) DELETE 审批通过后真删。它同时就是你的业务删除方法
@Override
public void delete(String id, String userId, String orgId) { purchaseMapper.deleteByPrimaryKey(id); }

// 2) 编辑前快照：返回的是「更新请求 DTO」的 JSON，不是实体 JSON
@Override
public String getPreUpdateSnapshotData(String id, String userId, String orgId) {
    Purchase p = purchaseMapper.selectByPrimaryKey(id);
    PurchaseUpdateRequest snapshot = BeanUtils.copyBean(new PurchaseUpdateRequest(), p);
    snapshot.setUpdateType(ApprovalResourceUpdateType.APPROVAL.getValue()); // 关键
    return JSON.toJSONString(snapshot);
}

// 3) 回滚：解析快照 → 走代理调 update（updateType=approval 时切面放行，不会二次提审）
@Override
public void revertToSnapshot(String id, String userId, String orgId, String data) {
    CommonBeanFactory.getBean(PurchaseService.class)
        .update(JSON.parseObject(data, PurchaseUpdateRequest.class), userId, orgId);
}
```

三个容易写错的点：

- `getPreUpdateSnapshotData` 里必须带 `updateType=approval`，否则回滚那次 update 会被当成一次新编辑，又提审一次
- 回滚**必须走代理**（`CommonBeanFactory.getBean(...)`）。`this.update()` 会绕过 Spring 切面，`@HitApproval` 不生效也不报错
- `delete()` 上不要再挂 `@HitApproval`。引擎在 DELETE 审批通过后会调它，重复挂注解就无限递归（引擎用 ThreadLocal 跳过了，但这是引擎的保护，不是你的设计）

无快照表时 `updateSnapshotApprovalStatus` 空实现即可。

### 4.5 挂注解

```java
@HitApproval(formKey = FormKey.PURCHASE, executeType = CREATE, operatorId = "{#userId}")
@HitApproval(formKey = FormKey.PURCHASE, executeType = UPDATE, resourceId = "{#request.id}",
             updateType = "{#request.updateType}", operatorId = "{#userId}", comment = "{#request.comment}")
@HitApproval(formKey = FormKey.PURCHASE, executeType = DELETE, resourceId = "{#id}", operatorId = "{#userId}")
```

`formKey` 有默认值 `FormKey.ORDER` —— 写漏不报错，静默按订单走。这是注解设计上的坑，务必显式写。

同时业务代码要遵守时序契约（这条比任何一个登记点都重要）：

| 时机 | 引擎 | 你要做的 |
| --- | --- | --- |
| CREATE | 落库后置 `PENDING` | 新建时 `approval_status=NONE`；提供"提审"按钮 |
| UPDATE | 存快照 → 更新 → **事务提交后**自动提审 | update 里保留原 `approval_status`/`approved`，别被请求覆盖成 null |
| DELETE | 不删，只建 DELETE 实例 | 真删写在 handler.delete 里 |
| 驳回/撤回 | 回调 `revertToSnapshot` | 内部 update 带 `updateType=approval` |
| 通过 | 引擎直接写 `approval_status`/`approved` | 业务代码不要再维护这两个字段 |

**为什么"事务提交后"这么强调**：`afterCommit` 时原事务的 Connection 仍绑在当前线程，普通 `@Transactional` 会复用已提交的连接读到旧数据 —— 结果就是条件分支按编辑前的值判断。引擎用 `pushInNewTransaction`（REQUIRES_NEW）解决，你不需要管，但要知道为什么"我刚改的值没生效"不一定是你的 bug。

### 4.6 三个"不补就废"的登记点

这三个和 4.1 那种"静默降级"不同，是硬卡点，本次实践中全部踩到：

**① `validateFormType` 白名单**（`ApprovalFlowService`）

```java
for (ApprovalFormTypeEnum type : new ApprovalFormTypeEnum[] {
        CLUE, CUSTOMER, OPPORTUNITY, QUOTATION, CONTRACT, INVOICE, ORDER }) { ... }
```

硬编码数组，不遍历枚举。不加 → `POST /approval-flow` 抛 `module.form.illegal`，报错指不到这里。这是本次最费时的一处（约半天）。

**② `FieldSourceServiceProvider` 是四层，不是两行 put**

```
FieldSourceType 枚举  →  sources.put(type, new SourceReader(svc::getSimple, svc::batchGetSimpleByIds))
                      →  approvalSources.put(formKey, sources.get(type))
                      →  approvalFields.put(formKey, svc::getFieldValues)
```

漏任一层：条件永远判空（不报错，只是"条件分支怎么都不走"）。

**③ 通知要"3 个常量 + 1 条数据"**

`NotificationConstants` 的 `Module` / `Event` / `TemplateText` 三个常量 + i18n 文案 + **`sys_message_task` 里一行 `(event, task_type, sys_enable=1)`**。少最后这条：发送方按 `(module, event)` 查不到配置，静默不发。

### 4.7 其余登记点

显示名、日志模块、待办资源名、名称搜索别名、评论通知 switch —— 这些不补就是"待办列表里这一行是空的""日志模块是 null"，功能不崩，但体验残缺。清单见 guide 阶段二第 12 步。

**一个好消息**：给 `FormKey` 加枚举值会让两处穷举 `switch`（`McpFormulaController`、`StandardRelatedDetailTabDataHandler`）**编译失败**。这不是麻烦，是编译器替你查漏。所以每次改完 `mvn compile` 一遍。

---

## 五、第 3 步：后端验收（约半天）

先解决"怎么调接口"这件事，它比想象中麻烦：

### 5.1 本地调 API 的正确姿势（踩坑记录）

- 登录接口的用户名/密码 **RSA 加密**，不能用明文 curl。取 `GET /get-key` 拿公钥，用 `PKCS#1 v1.5`（`Cipher.getInstance("RSA")`）加密
- 登录后不是 cookie，是把返回的 `sessionId` / `csrfToken` 放进请求头：`X-AUTH-TOKEN`、`CSRF-TOKEN`、`Organization-Id`
- 建流程的 payload 里 `requireApproveComment`、`requireRejectComment`、`statusPermissions` **不能为 null**（DB 列 NOT NULL），否则报 SQL 约束错

### 5.2 建流程的 payload 结构

```json
{
  "name": "采购单审批", "formType": "purchase", "enable": true,
  "createExecute": true, "updateExecute": true, "deleteExecute": true,
  "statusPermissions": [],
  "createNodeConfig": {
    "nodes": [
      { "id": "s", "nodeType": "START" },
      { "id": "a", "nodeType": "APPROVER", "approvalType": "MANUAL",
        "multiApproverMode": "ALL", "emptyApproverAction": "AUTO_PASS",
        "sameSubmitterAction": "ALLOW", "approverType": "MEMBER", "approverList": ["admin"] },
      { "id": "e", "nodeType": "END" }
    ],
    "links": [{ "fromNodeId": "s", "toNodeId": "a" }, { "fromNodeId": "a", "toNodeId": "e" }]
  }
}
```

`sameSubmitterAction: "ALLOW"` 是单人环境下的测试技巧：默认 `SKIP` 会让"自己提审自己审批"直接跳过，你根本看不到审批按钮。想测同意/驳回就设成 `ALLOW`。

### 5.3 14 条验收

按 guide 阶段四那张表逐条过。其中两条最能说明问题：

- **编辑后自动提审**：改完立刻查库，应出现 `execute_time=UPDATE` 的实例，且 `approvalResource_snapshot` 里有编辑前的值
- **条件分支**：配一个 `amount >= 1000` 的条件节点，分别提审 5000 和 500 两条。前者产生审批待办，后者直接自动通过。**这条过了才说明 `FieldSourceServiceProvider` 真的生效**（否则两条都会走 DEFAULT）

全部走完后查三张表复核：`purchase`（状态）、`approval_instance`（实例）、`sys_notification`（通知）。

---

## 六、第 4 步：前端接入（约 1 天）

### 6.1 为什么前端注册点比想象多

前端有 **8 处**，其中 4 处是 TypeScript 的 `Record<FormDesignKeyEnum, ...>` 穷举表。加枚举值 → 4 个 Record 全部报错 → 按报错补。

这是**比后端友好的地方**：后端一半的登记点是静默降级，前端全是编译错误。所以前端不要怕加枚举，让 TS 带你走。

| # | 位置 | 漏了会怎样 |
| --- | --- | --- |
| 1 | `lib-shared/enums/formDesignEnum.ts` | 后面全部编译报错（这是入口） |
| 2-5 | `crm-form-create/config.ts` 的 4 个 Record | 编译报错 |
| 6 | `config/process.ts` 的 `businessTypeOptions` | 管理员的流程设计器里选不到 |
| 7 | `crm-approval-detail.vue` 的 `approvalStandardFormKeys` | 加签时你的 formType 被当成 `customFormId` 传 |
| 8 | `packages/mobile/src/api/modules/index.ts` | mobile 的 `type:check` 失败（mobile 复用 web 的 form 组件，但 `@/api/modules` 解析到自己的索引） |

第 8 处最反直觉：你只改了 web，但 pre-commit 会在 mobile 上挂。原因是 web 的 `crm-form-create/config.ts` 被 mobile 引用。

### 6.2 详情接口必须是 `FormDetail` 形状

```json
{ "id": "...", "name": "测试单", "amount": 5000, "moduleFields": [] }
```

`moduleFields` 缺失 → TS 报错（模型定义里是必填）；字段名对不上 `businessKey` → 页面渲染空白但接口 200。这两个失败模式完全不同，别混。

### 6.3 复用审批 UI：只有插槽需要你想

右侧（审批链、记录、评论、同意/驳回/加签/退回）全在 `CrmApprovalDetail` 里，你只要填左侧插槽：

```vue
<CrmApprovalDetail :form-key="FormDesignKeyEnum.PURCHASE" :source-id="id">
  <template #left="{ fieldPermissions, taskNode, setLoading, setEditing }">
    <ApprovalFieldEditor
      :form-key="FormDesignKeyEnum.PURCHASE"
      :source-id="id"
      :field-permissions="fieldPermissions"
      :task-node="taskNode"
      :set-loading="setLoading"
      :set-editing="setEditing"
    />
  </template>
</CrmApprovalDetail>
```

**四个插槽参数原样透传，不要自己算**：`fieldPermissions` 是引擎按节点下发的字段权限（只读/可编辑），`taskNode` 是当前待办节点，两个 `set*` 是给子组件回报加载/编辑态用的。`ApprovalFieldEditor` 内部已经处理了"详情态 ↔ 编辑态"切换，以及审批态保存时自动带 `updateType: 'approval'`。

`@saveApproval` 的契约是回调：`await callback()` 之后自己刷新详情，**不要另发保存请求**。

---

## 七、决策回顾：如果重来一次

| 决策 | 这次的选法 | 结论 |
| --- | --- | --- |
| 路线 | B（自有表） | 对。强 schema + 自己的 API，后面加字段不用动引擎 |
| 第一个实体 | 新建玩具表 `purchase` | 对。但**别拿已有实体改造**（比如给 product 加审批），它的历史包袱会污染你的判断 |
| 表单配置 | 先 SQL 拷 order，再用 `/module/form/save` 覆盖成干净字段 | 对。拷是为了让行存在（save 要求已存在），覆盖是为了字段名干净 |
| 验收方式 | 纯 curl + 查库，前端最后做 | 对。省掉大量"到底哪层错了"的排查 |
| 前端详情页 | 不新写，`CrmApprovalDetail` + `ApprovalFieldEditor` | 对。手写左侧插槽是最容易跑偏的路径 |
| 测试账号 | 一直用 admin | 有隐患。admin 前后端都直通权限，**必须补一个普通用户验数据权限** |

### 还没验证的（如果要做真模块，先补这些）

1. **数据权限拦截路径**：provider 写了，但没造第二个用户验证"非参与人 + 无数据权限会被拦"
2. **`updateApprovalPostField`**：样例里是空实现（注释标了 `ponytail:`），"审批通过后改字段"在这套代码上等于没接
3. **评论通知**：`ApprovalCommentNoticeService` 的 switch 加了，但没发过评论
4. **邮件/企微/钉钉/飞书**：只开了站内信
5. **表单设计器编辑**：模块表单配置是 SQL 种的，没验证设计器能不能正常编辑

---

## 八、失败模式速查（按"症状"找原因）

| 症状 | 最可能的原因 |
| --- | --- |
| 保存流程报 `module.form.illegal` | 4.6 ① `validateFormType` 白名单没加 |
| 保存流程报 404「资源不存在」 | 3.2 `sys_module_form` 没种 |
| 审批状态不写回业务表 | 4.2 表名映射没加 |
| 条件分支怎么配都不生效 | 4.6 ② `FieldSourceServiceProvider` 漏一层 |
| 待办列表这一行名字是空的 | 4.7 `ApprovalTodoService.loadResourceNameMap` 没加 |
| 审批通过后收不到通知 | 4.6 ③ `sys_message_task` 少一行 |
| 驳回后数据没回滚 | `getPreUpdateSnapshotData` 返回的不是更新请求 JSON，或回滚没走代理 |
| 驳回后立刻又进入审批中 | 回滚时 `updateType` 不是 `approval` |
| 编辑一条老数据被要求重新审批 | 3.1 `approved` 没回填 |
| 前端详情页空白但接口 200 | 6.2 字段名与 `businessKey` 对不上 |
| mobile 的 `type:check` 挂了 | 6.1 第 8 处 |

---

## 九、工作量基线

`purchase` 实测（一个人，含调试）：

| 阶段 | 时间 | 主要消耗 |
| --- | --- | --- |
| 库与配置 | 1h | 建表、拷 module_form、权限三处 |
| 后端接入 | 1d | 一半时间花在 `validateFormType` 和 module_form 这两个坑上 |
| 后端验收 | 0.5d | 登录加密 + 建流程 payload 的试错 |
| 前端接入 | 1d | 4 个 Record + 页面骨架 + lint/type 反复 |
| **合计** | **约 3 人日** | 第二个实体复制约 1 人日 |

真实模块（有快照表、子表、自定义字段）在此基础上 +1~2 人日。

---

## 十、边界：什么情况下不该接入审批

审批引擎适合"单据进出闸门 + 审批链可配"。以下需求它做不了，别硬塞：

- 审批过程中要改单据的**多版本草稿**（引擎只有"编辑前一份快照"）
- 复杂的**并行会签 + 动态会签人**（有加签，但没有"按字段值动态决定会签人"）
- 审批链需要**跨流程编排**（一个流程结束后自动触发另一个流程）
- 需要**按审批结果生成新业务实体**（只能靠后置字段更新或 WebHook 自己干）

这几种情况，用工作流引擎（Flowable/Camunda）或自己写状态机更合适，审批模块只当 UI 复用。
