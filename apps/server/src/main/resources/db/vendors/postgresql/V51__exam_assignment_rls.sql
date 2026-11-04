-- Lớp bảo vệ thứ hai cho ba bảng của khối giao bài/làm bài, theo đúng khuôn của khung tenancy: mỗi bảng bật
-- Row Level Security với policy riêng trên khoá phân vùng của chính nó, để role runtime lms_server_app (đã
-- được cấp quyền dữ kiện trên mọi bảng mới bởi default privileges của migration V3) chỉ thấy và chỉ ghi được
-- hàng của phân vùng đã đặt trong giao dịch.
alter table exam_assignment enable row level security;

create policy exam_assignment_partition_access on exam_assignment
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table exam_attempt enable row level security;

create policy exam_attempt_partition_access on exam_attempt
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

alter table attempt_answer enable row level security;

create policy attempt_answer_partition_access on attempt_answer
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));

-- Khoá ngoại hợp thành tới lớp của khối tổ chức dạy học chỉ tạo được khi bảng teaching_class đã tồn tại:
-- khối đó đi bằng migration V20 trên branch riêng, nên trên branch này (chưa hợp nhất) bảng chưa có và lệnh
-- tạo khoá ngoại bị bỏ qua có điều kiện; khi hai khối đã hợp nhất, migration này chạy sau V20 và khoá ngoại
-- được tạo đầy đủ. DO là khối mã vô danh của PostgreSQL, chạy một lần duy nhất khi migration áp.
do $$
begin
    if exists (select from information_schema.tables
               where table_schema = 'public' and table_name = 'teaching_class') then
        alter table exam_assignment
            add constraint fk_exam_assignment_class
            foreign key (class_id, partition_key) references teaching_class (id, partition_key);
    end if;
end
$$;
