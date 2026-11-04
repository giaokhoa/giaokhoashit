-- Row Level Security cho bảng tệp bài làm, chỉ có ở PostgreSQL, theo khuôn V51: policy so sánh partition_key
-- của hàng với biến giao dịch app.partition_key cho cả đường đọc và đường ghi. Số hiệu 96.1 giữ lớp bảo vệ
-- này trong đúng phân bổ V96 của lát tệp bài làm mà không chiếm số nguyên của các lát sau. Tài khoản runtime
-- lms_server_app đã được cấp quyền dữ kiện trên bảng mới qua default privileges ở V3 nên migration này chỉ
-- bật RLS và tạo policy.
alter table submission_file enable row level security;
create policy submission_file_partition_access on submission_file
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));
