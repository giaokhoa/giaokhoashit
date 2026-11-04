-- Lớp bảo vệ thứ hai ở tầng database cho khung tenancy, chỉ có ở PostgreSQL: Row Level Security trên hai bảng
-- hiện có, cộng role runtime riêng cho ứng dụng. Tách tài khoản là điều kiện bắt buộc để RLS có hiệu lực thật:
-- superuser, role có BYPASSRLS và chủ sở hữu bảng đều không bị policy chặn, mà tài khoản lms_server dùng cho
-- Flyway chính là POSTGRES_USER của container server-db (superuser, chủ mọi bảng) — nên ứng dụng chạy bằng role
-- lms_server_app không superuser, không BYPASSRLS, không sở hữu bảng nào, chỉ được cấp đúng quyền dữ kiện.

do $$
begin
    if not exists (select from pg_catalog.pg_roles where rolname = 'lms_server_app') then
        execute format('create role lms_server_app login password %L', '${lms-server-app-password}');
    end if;
end
$$;

alter role lms_server_app nosuperuser nobypassrls nocreatedb nocreaterole;

grant select, insert, update, delete on table app_user, tenant_record to lms_server_app;

-- Bảng do các migration sau tạo (vẫn chạy bằng tài khoản quản trị) tự được cấp quyền dữ kiện cho role runtime.
alter default privileges in schema public
    grant select, insert, update, delete on tables to lms_server_app;

-- Mỗi phiên chỉ thấy và chỉ ghi được đúng hàng hồ sơ của chính sub trong token của nó; biến ngữ cảnh do ứng dụng
-- đặt theo từng giao dịch, chưa đặt thì phép so sánh với NULL không đúng cho bất kỳ hàng nào.
alter table app_user enable row level security;

create policy app_user_self_access on app_user
    using (keycloak_sub = current_setting('app.current_sub', true))
    with check (keycloak_sub = current_setting('app.current_sub', true));

-- Hàng của bảng phân vùng chỉ hiện trong giao dịch đã đặt app.partition_key bằng đúng khoá của hàng.
alter table tenant_record enable row level security;

create policy tenant_record_partition_access on tenant_record
    using (partition_key = current_setting('app.partition_key', true))
    with check (partition_key = current_setting('app.partition_key', true));
