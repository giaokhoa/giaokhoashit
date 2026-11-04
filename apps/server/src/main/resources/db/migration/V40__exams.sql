-- Bảng nội dung của đề: exam là tài nguyên, exam_version là phiên bản bất biến sau phát hành, exam_item là mục
-- trỏ tới đúng một phiên bản câu cụ thể, exam_item_snapshot là nội dung đóng băng của từng mục tại thời điểm phát
-- hành. Mọi bảng mang partition_key theo khuôn khung tenancy và nối nhau bằng khoá ngoại hợp thành kèm khoá phân
-- vùng để một hàng con không gắn được sang hàng cha thuộc phân vùng khác.

create table exam (
    id uuid primary key,
    partition_key varchar(255) not null,
    workspace_kind varchar(16) not null,
    owner_type varchar(16) not null,
    owner_user_sub varchar(255),
    owner_organization_id varchar(255),
    collection_id uuid,
    custody_kind varchar(16) not null,
    title varchar(500) not null,
    created_by_sub varchar(255) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint exam_workspace_kind_known check (workspace_kind in ('personal', 'center', 'system')),
    constraint exam_owner_type_known check (owner_type in ('user', 'organization', 'platform')),
    constraint exam_custody_kind_known check (custody_kind in ('owned', 'licensed')),
    constraint exam_workspace_owner_consistent check (
        (workspace_kind = 'personal' and owner_type = 'user' and owner_user_sub is not null
            and owner_organization_id is null)
        or (workspace_kind = 'center' and owner_type = 'organization' and owner_organization_id is not null
            and owner_user_sub is null)
        or (workspace_kind = 'system' and owner_type = 'platform' and owner_user_sub is null
            and owner_organization_id is null)
    ),
    constraint exam_identity_in_partition unique (id, partition_key)
);

create table exam_version (
    id uuid primary key,
    exam_id uuid not null,
    partition_key varchar(255) not null,
    version_number integer not null,
    status varchar(16) not null,
    content_hash varchar(128),
    lock_version bigint not null default 0,
    created_by_sub varchar(255) not null,
    published_at timestamp with time zone,
    locked_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint exam_version_status_known check (status in ('draft', 'published')),
    constraint exam_version_number_per_exam unique (exam_id, version_number),
    constraint exam_version_exam_same_partition foreign key (exam_id, partition_key)
        references exam (id, partition_key),
    constraint exam_version_identity_in_partition unique (id, partition_key)
);

create table exam_item (
    id uuid primary key,
    exam_version_id uuid not null,
    exam_id uuid not null,
    partition_key varchar(255) not null,
    question_version_id uuid not null,
    position integer not null,
    score numeric(10, 2) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint exam_item_position_per_version unique (exam_version_id, position),
    constraint exam_item_version_same_partition foreign key (exam_version_id, partition_key)
        references exam_version (id, partition_key),
    constraint exam_item_identity_in_partition unique (id, partition_key)
);

-- Nội dung đóng băng lưu dạng text, không dùng jsonb: jsonb không giữ thứ tự khoá và khoảng trắng của đầu vào
-- (tài liệu PostgreSQL về kiểu JSON) nên sẽ phá tính nguyên vẹn từng ký tự mà content_hash phải bảo chứng.
create table exam_item_snapshot (
    id uuid primary key,
    exam_item_id uuid not null,
    exam_version_id uuid not null,
    partition_key varchar(255) not null,
    question_version_id uuid not null,
    question_type varchar(32) not null,
    position integer not null,
    score numeric(10, 2) not null,
    content text not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint exam_item_snapshot_one_per_item unique (exam_item_id),
    constraint exam_item_snapshot_item_same_partition foreign key (exam_item_id, partition_key)
        references exam_item (id, partition_key)
);
