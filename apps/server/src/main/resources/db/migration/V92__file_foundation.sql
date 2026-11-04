-- Nền tệp của gói tệp + LaTeX: ba bảng của mô hình ba lớp — blob bất biến, phiên tải lên và liên kết tệp theo
-- ngữ cảnh. Lớp nghiệp vụ (tài nguyên/phiên bản) là hai bảng resource/resource_version của lõi quyền; liên kết
-- trỏ vào đúng phiên bản qua khoá ngoại kép cùng khuôn của lõi. Mọi bảng mang partition_key của phân vùng chứa
-- tài nguyên đích; lớp Row Level Security trên PostgreSQL nằm ở migration vendor V93, theo khuôn V11.

create table file_blob (
    id uuid primary key,
    storage_key varchar(255) not null unique,
    content_type varchar(64) not null,
    size_bytes bigint not null,
    checksum_sha256 varchar(64) not null,
    width integer,
    height integer,
    status varchar(32) not null,
    uploaded_by_sub varchar(255) not null,
    partition_key varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint file_blob_status check (status in ('READY', 'QUARANTINED')),
    constraint file_blob_size_positive check (size_bytes > 0)
);

create table upload_session (
    id uuid primary key,
    uploader_sub varchar(255) not null,
    partition_key varchar(255) not null,
    target_resource_id uuid not null,
    target_version_id uuid not null,
    display_name varchar(255) not null,
    status varchar(32) not null,
    staging_key varchar(255),
    blob_id uuid references file_blob (id),
    link_id uuid,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint upload_session_status check (status in ('OPEN', 'READY', 'COMPLETED', 'EXPIRED')),
    constraint upload_session_target_fk foreign key (target_version_id, target_resource_id)
        references resource_version (id, resource_id)
);

create table file_link (
    id uuid primary key,
    resource_id uuid not null,
    resource_version_id uuid not null,
    position varchar(32) not null,
    block_id varchar(255),
    blob_id uuid not null references file_blob (id),
    display_name varchar(255) not null,
    session_id uuid unique references upload_session (id),
    partition_key varchar(255) not null,
    created_by_sub varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint file_link_position check (position in ('content', 'choice', 'answer', 'solution', 'document')),
    constraint file_link_version_fk foreign key (resource_version_id, resource_id)
        references resource_version (id, resource_id)
);

-- Ảnh của một khối công thức là một blob của nền tệp: gắn khoá ngoại từ cột image_blob_id của bảng khối (tạo ở
-- V90, trước khi bảng blob tồn tại) sang blob tại đây.
alter table question_version_block
    add constraint question_version_block_image_blob_fk foreign key (image_blob_id) references file_blob (id);

-- Dấu vết kiểm toán của các thao tác trên tệp và nguồn: chỉ ghi người thực hiện, hành động và các định danh
-- liên quan — không ghi token, khoá lưu trữ hay nội dung.
create table file_audit_log (
    id uuid primary key,
    actor_sub varchar(255) not null,
    action varchar(64) not null,
    resource_id uuid,
    resource_version_id uuid,
    blob_id uuid,
    link_id uuid,
    partition_key varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp
);
