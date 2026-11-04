-- Lớp bảo vệ thứ hai ở tầng database cho bảng khối nội dung của feat câu hỏi, chỉ có ở PostgreSQL, theo khuôn
-- V31: dữ kiện cá nhân chỉ hiện và chỉ ghi được cho đúng chủ sở hữu qua biến app.current_sub mà ứng dụng đặt ở
-- đầu mỗi giao dịch. Role runtime được cấp quyền dữ kiện trên bảng mới qua default privileges mà migration V3
-- đã đặt cho mọi bảng tạo sau.

alter table question_version_block enable row level security;

create policy question_version_block_owner_access on question_version_block
    using (owner_sub = current_setting('app.current_sub', true))
    with check (owner_sub = current_setting('app.current_sub', true));
