package cn.cordys.crm.purchase.controller;

import cn.cordys.common.constants.FormKeyConstants;
import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.pager.Pager;
import cn.cordys.common.permission.CsPermission;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.purchase.domain.Purchase;
import cn.cordys.crm.purchase.dto.PurchaseGetResponse;
import cn.cordys.crm.purchase.dto.request.PurchaseAddRequest;
import cn.cordys.crm.purchase.dto.request.PurchasePageRequest;
import cn.cordys.crm.purchase.dto.request.PurchaseUpdateRequest;
import cn.cordys.crm.purchase.service.PurchaseService;
import cn.cordys.security.SessionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "采购单")
@RequestMapping("/purchase")
public class PurchaseController {

    @Resource
    private PurchaseService purchaseService;

    @PostMapping("/add")
    @CsPermission(PermissionConstants.PURCHASE_ADD)
    @Operation(summary = "添加采购单")
    public Purchase add(@Validated @RequestBody PurchaseAddRequest request) {
        return purchaseService.add(request, SessionUtils.getUserId(), OrganizationContext.getOrganizationId());
    }

    @PostMapping("/update")
    @CsPermission(value = PermissionConstants.PURCHASE_UPDATE, resourceId = "{#request.id}",
            formType = FormKeyConstants.PURCHASE)
    @Operation(summary = "更新采购单")
    public Purchase update(@Validated @RequestBody PurchaseUpdateRequest request) {
        return purchaseService.update(request, SessionUtils.getUserId(), OrganizationContext.getOrganizationId());
    }

    @GetMapping("/get/{id}")
    @CsPermission(value = PermissionConstants.PURCHASE_READ, resourceId = "{#id}",
            formType = FormKeyConstants.PURCHASE)
    @Operation(summary = "采购单详情")
    public PurchaseGetResponse get(@PathVariable String id) {
        return purchaseService.get(id);
    }

    @PostMapping("/page")
    @Operation(summary = "采购单列表")
    public Pager<List<PurchaseGetResponse>> page(@RequestBody PurchasePageRequest request) {
        return purchaseService.page(request, OrganizationContext.getOrganizationId());
    }

    @GetMapping("/delete/{id}")
    @CsPermission(value = PermissionConstants.PURCHASE_DELETE, resourceId = "{#id}",
            formType = FormKeyConstants.PURCHASE)
    @Operation(summary = "删除采购单")
    public void delete(@PathVariable String id) {
        purchaseService.deleteWithApprovalCheck(id, SessionUtils.getUserId(),
                OrganizationContext.getOrganizationId());
    }
}
