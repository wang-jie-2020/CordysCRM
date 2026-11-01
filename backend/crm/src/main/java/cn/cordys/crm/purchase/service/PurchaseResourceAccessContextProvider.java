package cn.cordys.crm.purchase.service;

import cn.cordys.common.constants.FormKey;
import cn.cordys.common.permission.ResourceAccessContext;
import cn.cordys.common.permission.ResourceAccessContextProvider;
import cn.cordys.crm.purchase.domain.Purchase;
import cn.cordys.mybatis.BaseMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PurchaseResourceAccessContextProvider implements ResourceAccessContextProvider {

    @Resource
    private BaseMapper<Purchase> purchaseMapper;

    @Override
    public String getFormType() {
        return FormKey.PURCHASE.getKey();
    }

    @Override
    public ResourceAccessContext getAccessContext(String resourceId, String orgId) {
        Purchase purchase = purchaseMapper.selectByPrimaryKey(resourceId);
        if (purchase == null) {
            return null;
        }
        ResourceAccessContext context = new ResourceAccessContext();
        context.setOwnerId(purchase.getOwner());
        context.setApprovalStatus(purchase.getApprovalStatus());
        return context;
    }

    @Override
    public Map<String, String> batchGetOwnerIds(List<String> resourceIds, String orgId) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        return purchaseMapper.selectByIds(resourceIds).stream()
                .filter(purchase -> purchase.getOwner() != null)
                .collect(Collectors.toMap(Purchase::getId, Purchase::getOwner, (a, b) -> a));
    }
}
