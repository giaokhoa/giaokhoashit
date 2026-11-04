-- Bảng khung của khung tenancy: mọi hàng thuộc đúng một phân vùng qua partition_key (Organization ID của trung
-- tâm, user:<sub> của tài khoản cá nhân, hoặc system), owner_sub ghi người tạo hàng. Bảng này chỉ để kiểm chứng
-- discriminator và RLS của khung, chưa mang dữ kiện nghiệp vụ.
create table tenant_record (
    id uuid primary key,
    partition_key varchar(255) not null,
    owner_sub varchar(255) not null,
    content text,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);
