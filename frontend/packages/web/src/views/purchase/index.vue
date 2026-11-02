<template>
  <div class="h-full bg-[var(--text-n9)] p-[16px]">
    <CrmCard hide-footer>
      <div class="mb-[16px] flex items-center justify-between">
        <div class="text-[16px] font-medium">{{ t('module.purchase') }}</div>
        <n-button type="primary" @click="handleAdd">{{ t('common.add') }}</n-button>
      </div>
      <n-data-table :columns="columns" :data="list" :loading="loading" :row-props="rowProps" :bordered="false" />
    </CrmCard>

    <CrmFormCreateDrawer
      v-model:visible="createVisible"
      :form-key="FormDesignKeyEnum.PURCHASE"
      :source-id="editId"
      @saved="handleSaved"
    />

    <PurchaseDetail v-model:show="detailVisible" :source-id="activeId" @refresh="loadList" />
  </div>
</template>

<script setup lang="ts">
  import { h, onMounted, ref } from 'vue';
  import { NButton, NDataTable, useMessage } from 'naive-ui';

  import { FormDesignKeyEnum } from '@lib/shared/enums/formDesignEnum';
  import { ProcessStatusEnum } from '@lib/shared/enums/process';
  import { useI18n } from '@lib/shared/hooks/useI18n';
  import type { PurchaseItem } from '@lib/shared/models/purchase';

  import CrmCard from '@/components/pure/crm-card/index.vue';
  import CrmApprovalStatus from '@/components/business/crm-approval/components/crm-approval-status.vue';
  import CrmFormCreateDrawer from '@/components/business/crm-form-create-drawer/index.vue';
  import PurchaseDetail from './detail.vue';

  import { getPurchaseList, reviewResource } from '@/api/modules';

  const { t } = useI18n();
  const Message = useMessage();

  const loading = ref(false);
  const list = ref<PurchaseItem[]>([]);
  const createVisible = ref(false);
  const editId = ref<string>();
  const detailVisible = ref(false);
  const activeId = ref<string>();

  async function loadList() {
    loading.value = true;
    try {
      const res = await getPurchaseList({ current: 1, pageSize: 100 });
      list.value = res.list;
    } finally {
      loading.value = false;
    }
  }

  function stop(e: Event) {
    e.stopPropagation();
  }

  function openDetail(e: Event, row: PurchaseItem) {
    stop(e);
    activeId.value = row.id;
    detailVisible.value = true;
  }

  async function handleReview(e: Event, row: PurchaseItem) {
    stop(e);
    await reviewResource({ resourceId: row.id, formKey: FormDesignKeyEnum.PURCHASE });
    Message.success(t('common.reviewSuccess'));
    loadList();
  }

  const columns = [
    { title: t('common.name'), key: 'name' },
    { title: t('crmFormDesign.amount'), key: 'amount' },
    {
      title: t('common.status'),
      key: 'approvalStatus',
      render: (row: PurchaseItem) => h(CrmApprovalStatus, { status: row.approvalStatus || ProcessStatusEnum.NONE }),
    },
    {
      title: t('common.operation'),
      key: 'operation',
      render: (row: PurchaseItem) =>
        h('div', { class: 'flex items-center gap-[8px]' }, [
          h(NButton, { text: true, type: 'primary', onClick: (e: Event) => openDetail(e, row) }, () =>
            t('common.detail')
          ),
          h(
            NButton,
            {
              text: true,
              type: 'primary',
              disabled: row.approvalStatus === ProcessStatusEnum.APPROVING,
              onClick: (e: Event) => handleReview(e, row),
            },
            () => t('common.review')
          ),
        ]),
    },
  ];

  const rowProps = (row: PurchaseItem) => ({
    style: 'cursor: pointer',
    onClick: () => {
      activeId.value = row.id;
      detailVisible.value = true;
    },
  });

  function handleAdd() {
    editId.value = undefined;
    createVisible.value = true;
  }

  function handleSaved() {
    createVisible.value = false;
    loadList();
  }

  onMounted(loadList);
</script>
