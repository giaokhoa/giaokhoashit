-- Danh tính (username, email, họ tên, credential) thuộc về Keycloak; bảng này chỉ là khung liên kết giữa
-- `sub` của token và bản ghi người dùng phía LMS. Hồ sơ nghiệp vụ bổ sung thuộc các feat sau.
create table app_user (
    id uuid primary key,
    keycloak_sub varchar(255) not null unique,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);
