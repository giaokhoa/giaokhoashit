-- Lớp bảo vệ thứ hai ở tầng database cho các bảng của feat câu hỏi, chỉ có ở PostgreSQL: dữ kiện cá nhân chỉ
-- hiện và chỉ ghi được cho đúng chủ sở hữu, qua biến app.current_sub mà ứng dụng đặt ở đầu mỗi giao dịch — cùng
-- mẫu policy của bảng app_user ở migration V3. Chưa đặt biến thì phép so sánh với NULL không đúng cho bất kỳ
-- hàng nào; role runtime không có đường vòng qua các policy này (không superuser, không BYPASSRLS, không sở
-- hữu bảng — xem V3).
alter table question_bank enable row level security;

create policy question_bank_owner_access on question_bank
    using (owner_sub = current_setting('app.current_sub', true))
    with check (owner_sub = current_setting('app.current_sub', true));

alter table question enable row level security;

create policy question_owner_access on question
    using (owner_sub = current_setting('app.current_sub', true))
    with check (owner_sub = current_setting('app.current_sub', true));

alter table question_version enable row level security;

create policy question_version_owner_access on question_version
    using (owner_sub = current_setting('app.current_sub', true))
    with check (owner_sub = current_setting('app.current_sub', true));

alter table bank_entry enable row level security;

create policy bank_entry_owner_access on bank_entry
    using (owner_sub = current_setting('app.current_sub', true))
    with check (owner_sub = current_setting('app.current_sub', true));
