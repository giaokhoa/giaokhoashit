-- Row Level Security cho ba bảng của khối chấm điểm, chỉ có ở PostgreSQL, theo khuôn V3: mỗi bảng một policy so
-- sánh partition_key của hàng với biến giao dịch app.partition_key cho cả đường đọc và đường ghi. Tài khoản
-- runtime lms_server_app đã được cấp quyền dữ kiện trên các bảng mới qua default privileges ở V3 nên migration
-- này chỉ bật RLS và tạo policy.

alter table attempt_item_grade enable row level security;
create policy attempt_item_grade_partition_access on attempt_item_grade
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table attempt_grade_summary enable row level security;
create policy attempt_grade_summary_partition_access on attempt_grade_summary
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table assignment_publication enable row level security;
create policy assignment_publication_partition_access on assignment_publication
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

-- Khoá ngoại hợp thành sang exam_attempt và exam_assignment của khối giao/làm bài: hai bảng đó thuộc khoảng
-- migration V50–V59 và đi vào chuỗi tích hợp trước khoảng V60–V69, nhưng branch riêng của khối này chưa có
-- chúng. Khối DO dưới đây gắn khoá ngoại ngay khi bảng đích đã tồn tại trong chuỗi migration đang chạy (đúng
-- khuôn kiểm tra tồn tại của V3) và bỏ qua khi chưa có; thứ tự hợp nhất bắt buộc khối giao/làm bài vào trước
-- khối này, nếu đổi thứ tự thì phải bổ sung khoá ngoại bằng một migration mới trong khoảng V60–V69.
do $$
begin
    if exists (select from information_schema.tables
               where table_schema = 'public' and table_name = 'exam_attempt') then
        alter table attempt_item_grade
            add constraint attempt_item_grade_attempt_fk
            foreign key (attempt_id, partition_key) references exam_attempt (id, partition_key);
        alter table attempt_grade_summary
            add constraint attempt_grade_summary_attempt_fk
            foreign key (attempt_id, partition_key) references exam_attempt (id, partition_key);
    end if;
    if exists (select from information_schema.tables
               where table_schema = 'public' and table_name = 'exam_assignment') then
        alter table assignment_publication
            add constraint assignment_publication_assignment_fk
            foreign key (assignment_id, partition_key) references exam_assignment (id, partition_key);
    end if;
end
$$;
