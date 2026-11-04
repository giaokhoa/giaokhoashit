-- Row Level Security cho bốn bảng nền tệp, chỉ có ở PostgreSQL, theo khuôn V11: mỗi bảng một policy so sánh
-- partition_key của hàng với biến giao dịch app.partition_key cho cả đường đọc và đường ghi. Tài khoản runtime
-- lms_server_app đã được cấp quyền dữ kiện trên các bảng mới qua default privileges ở V3 nên migration này chỉ
-- bật RLS và tạo policy.

alter table file_blob enable row level security;
create policy file_blob_partition_access on file_blob
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table upload_session enable row level security;
create policy upload_session_partition_access on upload_session
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table file_link enable row level security;
create policy file_link_partition_access on file_link
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table file_audit_log enable row level security;
create policy file_audit_log_partition_access on file_audit_log
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));
