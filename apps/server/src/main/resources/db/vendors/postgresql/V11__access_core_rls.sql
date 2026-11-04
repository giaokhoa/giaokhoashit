-- Row Level Security cho tám bảng lõi của lõi quyền, chỉ có ở PostgreSQL, theo khuôn V3: mỗi bảng một policy so
-- sánh partition_key của hàng với biến giao dịch app.partition_key cho cả đường đọc và đường ghi. Tài khoản
-- runtime lms_server_app đã được cấp quyền dữ kiện trên các bảng mới qua default privileges ở V3 nên migration
-- này chỉ bật RLS và tạo policy.

alter table resource enable row level security;
create policy resource_partition_access on resource
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table resource_version enable row level security;
create policy resource_version_partition_access on resource_version
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table resource_grant enable row level security;
create policy resource_grant_partition_access on resource_grant
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table collection_assignment enable row level security;
create policy collection_assignment_partition_access on collection_assignment
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table resource_dependency enable row level security;
create policy resource_dependency_partition_access on resource_dependency
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table handover_request enable row level security;
create policy handover_request_partition_access on handover_request
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table submission enable row level security;
create policy submission_partition_access on submission
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table submission_item enable row level security;
create policy submission_item_partition_access on submission_item
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));
