-- Danh tính (username, email, họ tên, credential) thuộc về Keycloak; bảng này chỉ giữ khoá liên kết theo `sub`
-- của token và phần hồ sơ bổ sung do LMS tự quản. Độ dài varchar khớp đúng giới hạn validation ở tầng API.
create table app_user (
    id uuid primary key,
    keycloak_sub varchar(255) not null unique,
    display_name varchar(200),
    avatar_url varchar(1000),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create table custom_field_definition (
    id uuid primary key,
    field_key varchar(64) not null unique,
    label varchar(200) not null,
    created_at timestamp with time zone not null default current_timestamp
);

create table user_custom_field_value (
    user_id uuid not null references app_user (id),
    definition_id uuid not null references custom_field_definition (id),
    field_value varchar(2000) not null,
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (user_id, definition_id)
);
