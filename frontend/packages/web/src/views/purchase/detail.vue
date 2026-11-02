<template>
  <CrmDrawer v-model:show="show" resizable no-padding :footer="false" :title="detailInfo?.name" :view-size="'large'">
    <template #titleLeft>
      <CrmApprovalStatus :status="detailInfo?.approvalStatus || ProcessStatusEnum.NONE" />
    </template>
    <div class="h-full bg-[var(--text-n9)] p-[16px]">
      <CrmCard content-height="100%" hide-footer no-content-padding>
        <CrmApprovalDetail
          v-if="show && sourceId"
          :form-key="FormDesignKeyEnum.PURCHASE"
          :source-id="sourceId"
          :approval-status="detailInfo?.approvalStatus || ProcessStatusEnum.NONE"
          left-loading
          @saveApproval="handleSaveApproval"
          @delete-approved="handleDeleteApproved"
        >
          <template #left="{ fieldPermissions, taskNode, setLoading, setEditing }">
            <ApprovalFieldEditor
              ref="fieldEditorRef"
              :form-key="FormDesignKeyEnum.PURCHASE"
              :source-id="sourceId"
              :field-permissions="fieldPermissions"
              :task-node="taskNode"
              :set-loading="setLoading"
              :set-editing="setEditing"
              @init="handleInit"
              @saved="handleSaved"
            />
          </template>
        </CrmApprovalDetail>
      </CrmCard>
    </div>
  </CrmDrawer>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue';

  import { FormDesignKeyEnum } from '@lib/shared/enums/formDesignEnum';
  import { ProcessStatusEnum } from '@lib/shared/enums/process';
  import type { PurchaseItem } from '@lib/shared/models/purchase';

  import CrmCard from '@/components/pure/crm-card/index.vue';
  import CrmDrawer from '@/components/pure/crm-drawer/index.vue';
  import ApprovalFieldEditor from '@/components/business/crm-approval/components/approvalFieldEditor.vue';
  import CrmApprovalDetail from '@/components/business/crm-approval/components/crm-approval-detail.vue';
  import CrmApprovalStatus from '@/components/business/crm-approval/components/crm-approval-status.vue';

  import { getPurchaseDetail } from '@/api/modules';

  const props = defineProps<{ show: boolean; sourceId?: string }>();
  const emit = defineEmits<{ (e: 'update:show', v: boolean): void; (e: 'refresh'): void }>();

  const show = computed({
    get: () => props.show,
    set: (v: boolean) => emit('update:show', v),
  });
  const detailInfo = ref<PurchaseItem>();

  async function initDetail() {
    if (!props.sourceId) return;
    detailInfo.value = await getPurchaseDetail(props.sourceId);
  }

  watch(
    () => props.show,
    (v) => {
      if (v) initDetail();
    }
  );

  function handleInit(_collaborationType?: unknown, _sourceName?: string, detail?: Record<string, any>) {
    if (detail?.approvalStatus) detailInfo.value = { ...detailInfo.value, ...detail } as PurchaseItem;
  }

  function handleSaved() {
    initDetail();
    emit('refresh');
  }

  async function handleSaveApproval(callback: () => Promise<any>) {
    await callback();
    initDetail();
    emit('refresh');
  }

  async function handleDeleteApproved() {
    detailInfo.value = await getPurchaseDetail(props.sourceId as string);
  }
</script>
