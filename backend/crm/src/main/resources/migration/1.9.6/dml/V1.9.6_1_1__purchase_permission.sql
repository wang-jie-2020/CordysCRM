-- 采购单角色权限
INSERT INTO sys_role_permission (id, role_id, permission_id)
VALUES (UUID_SHORT(), 'org_admin', 'PURCHASE:READ'),
       (UUID_SHORT(), 'org_admin', 'PURCHASE:ADD'),
       (UUID_SHORT(), 'org_admin', 'PURCHASE:UPDATE'),
       (UUID_SHORT(), 'org_admin', 'PURCHASE:DELETE'),
       (UUID_SHORT(), 'sales_manager', 'PURCHASE:READ'),
       (UUID_SHORT(), 'sales_staff', 'PURCHASE:READ');

-- 采购单审批结果消息任务
INSERT INTO sys_message_task (id, event, task_type, email_enable, sys_enable, organization_id, template, create_user,
                              create_time, update_user, update_time)
VALUES (UUID_SHORT(), 'PURCHASE_APPROVAL', 'PURCHASE', false, true, '100001', null, 'admin',
        UNIX_TIMESTAMP() * 1000 + 2, 'admin', UNIX_TIMESTAMP() * 1000 + 2);
