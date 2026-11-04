-- Lớp bảo vệ thứ hai ở tầng database cho ba bảng của khối tổ chức dạy học, cùng khuôn với chính sách của
-- tenant_record: hàng chỉ hiện và chỉ ghi được trong giao dịch đã đặt app.partition_key bằng đúng khoá của hàng,
-- chưa đặt biến thì phép so sánh với NULL không đúng cho bất kỳ hàng nào. Quyền dữ kiện cho role runtime
-- lms_server_app đã được cấp sẵn cho mọi bảng tạo về sau bởi default privileges trong V3, nên migration này chỉ
-- bật Row Level Security và tạo chính sách cho từng bảng.

alter table course enable row level security;

create policy course_partition_access on course
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table teaching_class enable row level security;

create policy teaching_class_partition_access on teaching_class
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table class_member enable row level security;

create policy class_member_partition_access on class_member
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));
