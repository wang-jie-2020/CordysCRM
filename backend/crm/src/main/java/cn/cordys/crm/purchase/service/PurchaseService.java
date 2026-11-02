package cn.cordys.crm.purchase.service;

import cn.cordys.aspectj.annotation.OperationLog;
import cn.cordys.aspectj.constants.LogModule;
import cn.cordys.aspectj.constants.LogType;
import cn.cordys.common.constants.FormKey;
import cn.cordys.common.exception.GenericException;
import cn.cordys.common.pager.PageUtils;
import cn.cordys.common.pager.Pager;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.common.util.BeanUtils;
import cn.cordys.common.util.CommonBeanFactory;
import cn.cordys.common.util.JSON;
import cn.cordys.common.util.Translator;
import cn.cordys.crm.approval.annotation.HitApproval;
import cn.cordys.crm.approval.constants.ApprovalResourceUpdateType;
import cn.cordys.crm.approval.constants.ApprovalStatus;
import cn.cordys.crm.approval.constants.ExecuteTimingEnum;
import cn.cordys.crm.approval.dto.ResourceApprovalPostUpdateParam;
import cn.cordys.crm.approval.dto.ResourceSnapshotApprovalParam;
import cn.cordys.crm.approval.handler.ApprovalResourceHandler;
import cn.cordys.crm.purchase.domain.Purchase;
import cn.cordys.crm.purchase.dto.PurchaseGetResponse;
import cn.cordys.crm.purchase.dto.request.PurchaseAddRequest;
import cn.cordys.crm.purchase.dto.request.PurchasePageRequest;
import cn.cordys.crm.purchase.dto.request.PurchaseUpdateRequest;
import cn.cordys.mybatis.BaseMapper;
import cn.cordys.mybatis.lambda.LambdaQueryWrapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 采购单服务。审批接入的完整样例：业务表 6 列 + handler + 3 个时机的注解。
 */
@Service
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class PurchaseService implements ApprovalResourceHandler {

    @Resource
    private BaseMapper<Purchase> purchaseMapper;

    @OperationLog(module = LogModule.PURCHASE_INDEX, type = LogType.ADD)
    @HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.CREATE, operatorId = "{#userId}")
    public Purchase add(PurchaseAddRequest request, String userId, String orgId) {
        Purchase purchase = BeanUtils.copyBean(new Purchase(), request);
        if (StringUtils.isBlank(purchase.getOwner())) {
            purchase.setOwner(userId);
        }
        long now = System.currentTimeMillis();
        purchase.setId(IDGenerator.nextStr());
        purchase.setOrganizationId(orgId);
        purchase.setApprovalStatus(ApprovalStatus.NONE.name());
        purchase.setApproved(false);
        purchase.setCreateUser(userId);
        purchase.setUpdateUser(userId);
        purchase.setCreateTime(now);
        purchase.setUpdateTime(now);
        purchaseMapper.insert(purchase);
        return purchase;
    }

    @OperationLog(module = LogModule.PURCHASE_INDEX, type = LogType.UPDATE, resourceId = "{#request.id}")
    @HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.UPDATE, resourceId = "{#request.id}",
            updateType = "{#request.updateType}", operatorId = "{#userId}", comment = "{#request.comment}")
    public Purchase update(PurchaseUpdateRequest request, String userId, String orgId) {
        Purchase origin = purchaseMapper.selectByPrimaryKey(request.getId());
        if (origin == null) {
            throw new GenericException(Translator.get("purchase.not_exist"));
        }
        Purchase purchase = BeanUtils.copyBean(new Purchase(), request);
        // 编辑与审批回退都不改审批状态，状态只由引擎回写
        purchase.setApprovalStatus(origin.getApprovalStatus());
        purchase.setApproved(origin.getApproved());
        purchase.setOrganizationId(origin.getOrganizationId());
        purchase.setCreateUser(origin.getCreateUser());
        purchase.setCreateTime(origin.getCreateTime());
        purchase.setUpdateUser(userId);
        purchase.setUpdateTime(System.currentTimeMillis());
        purchaseMapper.updateById(purchase);
        return purchase;
    }

    /**
     * 删除采购单（带审批校验）：命中审批流时只建 DELETE 实例，通过后才真正删除。
     */
    @HitApproval(formKey = FormKey.PURCHASE, executeType = ExecuteTimingEnum.DELETE, resourceId = "{#id}",
            operatorId = "{#userId}")
    public void deleteWithApprovalCheck(String id, String userId, String orgId) {
        // 自调用 bypass 代理，delete 上的 @OperationLog 不会生效，从容器取代理对象
        CommonBeanFactory.getBean(PurchaseService.class).delete(id, userId, orgId);
    }

    /**
     * 同时是 {@link ApprovalResourceHandler#delete}：引擎在 DELETE 审批通过后调用它真删。
     */
    @Override
    @OperationLog(module = LogModule.PURCHASE_INDEX, type = LogType.DELETE, resourceId = "{#id}")
    public void delete(String id, String userId, String orgId) {
        purchaseMapper.deleteByPrimaryKey(id);
    }

    public PurchaseGetResponse get(String id) {
        return getSimple(id);
    }

    public Pager<List<PurchaseGetResponse>> page(PurchasePageRequest request, String orgId) {
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        LambdaQueryWrapper<Purchase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Purchase::getOrganizationId, orgId)
                .like(Purchase::getName, request.getKeyword())
                .orderByDesc(Purchase::getCreateTime);
        List<PurchaseGetResponse> rows = purchaseMapper.selectListByLambda(wrapper).stream()
                .map(purchase -> BeanUtils.copyBean(new PurchaseGetResponse(), purchase))
                .toList();
        return PageUtils.setPageInfo(page, rows);
    }

    public PurchaseGetResponse getSimple(String id) {
        Purchase purchase = purchaseMapper.selectByPrimaryKey(id);
        return purchase == null ? null : BeanUtils.copyBean(new PurchaseGetResponse(), purchase);
    }

    public List<PurchaseGetResponse> batchGetSimpleByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return purchaseMapper.selectByIds(ids).stream()
                .map(purchase -> BeanUtils.copyBean(new PurchaseGetResponse(), purchase))
                .toList();
    }

    /**
     * 供引用字段展示用；本实体没有子表字段，与详情一致。
     */
    public PurchaseGetResponse getFieldValues(String id) {
        return getSimple(id);
    }

    @Override
    public FormKey getFormKey() {
        return FormKey.PURCHASE;
    }

    @Override
    public void updateSnapshotApprovalStatus(ResourceSnapshotApprovalParam param) {
        // 无快照表
    }

    @Override
    public void updateApprovalPostField(ResourceApprovalPostUpdateParam param) {
        // ponytail: 玩具实体没接 sys_module_form，审批后置字段更新不生效；接真实模块时按字段配置写主表/子表
    }

    @Override
    public void revertToSnapshot(String resourceId, String userId, String orgId, String snapshotData) {
        try {
            PurchaseUpdateRequest request = JSON.parseObject(snapshotData, PurchaseUpdateRequest.class);
            if (request == null) {
                return;
            }
            // updateType=approval 已在快照里，走代理调用时切面会放行，不会二次提审
            CommonBeanFactory.getBean(PurchaseService.class).update(request, userId, orgId);
        } catch (Exception e) {
            log.error("审批回退还原业务数据失败, resourceId:{}", resourceId, e);
        }
    }

    @Override
    public String getPreUpdateSnapshotData(String resourceId, String userId, String orgId) {
        Purchase purchase = purchaseMapper.selectByPrimaryKey(resourceId);
        if (purchase == null) {
            return null;
        }
        PurchaseUpdateRequest snapshot = BeanUtils.copyBean(new PurchaseUpdateRequest(), purchase);
        snapshot.setUpdateType(ApprovalResourceUpdateType.APPROVAL.getValue());
        return JSON.toJSONString(snapshot);
    }
}
