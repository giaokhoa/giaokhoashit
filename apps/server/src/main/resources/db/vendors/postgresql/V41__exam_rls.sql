-- RLS cho các bảng của đề theo cùng khuôn khung tenancy: mỗi bảng đúng một policy trên cột partition_key của
-- chính bảng đó, so với biến app.partition_key mà transaction manager đặt theo TenantContext ở đầu mỗi giao
-- dịch. current_setting(..., true) trả null khi biến chưa đặt và null không bằng bất kỳ khoá nào nên mặc định
-- không thấy hàng nào; role runtime lms_server_app (V3) không có BYPASSRLS nên không vượt được lớp này.

alter table exam enable row level security;

create policy exam_partition_isolation on exam
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table exam_version enable row level security;

create policy exam_version_partition_isolation on exam_version
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table exam_item enable row level security;

create policy exam_item_partition_isolation on exam_item
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table exam_item_snapshot enable row level security;

create policy exam_item_snapshot_partition_isolation on exam_item_snapshot
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));
