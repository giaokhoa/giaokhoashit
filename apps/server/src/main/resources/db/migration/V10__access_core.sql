-- Tám bảng lõi của lõi quyền tại API: đầu mối quyền của tài nguyên, phiên bản, grant, phân công, quan hệ phụ
-- thuộc và phần bàn giao dùng chung. Mọi bảng đều mang partition_key của phân vùng sở hữu dữ kiện; lớp Row Level
-- Security trên PostgreSQL nằm ở migration vendor V11, theo khuôn V2/V3 của khung tenancy.

create table resource (
    id uuid primary key,
    resource_type varchar(255) not null,
    workspace_kind varchar(32) not null,
    owner_type varchar(32) not null,
    owner_user_sub varchar(255),
    owner_organization_id varchar(255),
    collection_id uuid,
    partition_key varchar(255) not null,
    custody_kind varchar(32) not null,
    license_grant_id uuid,
    inherited_usage_limits text,
    source_resource_id uuid,
    source_version_id uuid,
    business_condition varchar(64),
    created_by_sub varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint resource_workspace_owner check (
        (workspace_kind = 'personal' and owner_type = 'user' and owner_user_sub is not null
            and owner_organization_id is null)
        or (workspace_kind = 'center' and owner_type = 'organization' and owner_organization_id is not null
            and owner_user_sub is null)
        or (workspace_kind = 'system' and owner_type = 'platform' and owner_user_sub is null
            and owner_organization_id is null)),
    constraint resource_custody check (
        (custody_kind = 'owned' and license_grant_id is null)
        or (custody_kind = 'licensed' and owner_type = 'organization' and license_grant_id is not null
            and source_resource_id is not null and source_version_id is not null)),
    constraint resource_id_partition unique (id, partition_key),
    constraint resource_collection_fk foreign key (collection_id, partition_key)
        references resource (id, partition_key)
);

create table resource_version (
    id uuid primary key,
    resource_id uuid not null,
    partition_key varchar(255) not null,
    version_number integer not null,
    status varchar(32) not null,
    content_hash varchar(255),
    created_by_sub varchar(255) not null,
    locked_at timestamp with time zone,
    published_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint resource_version_number_unique unique (resource_id, version_number),
    constraint resource_version_id_resource unique (id, resource_id),
    constraint resource_version_resource_fk foreign key (resource_id, partition_key)
        references resource (id, partition_key),
    constraint resource_version_status check (status in ('draft', 'published')),
    constraint resource_version_number_positive check (version_number > 0)
);

create table resource_grant (
    id uuid primary key,
    resource_id uuid not null,
    resource_version_id uuid not null,
    grantee_type varchar(32) not null,
    grantee_user_sub varchar(255),
    grantee_organization_id varchar(255),
    receiving_collection_id uuid,
    action varchar(64) not null,
    mode varchar(32),
    starts_at timestamp with time zone not null,
    expires_at timestamp with time zone,
    revocation_terms text,
    grantor_sub varchar(255) not null,
    source_partition_key varchar(255) not null,
    partition_key varchar(255) not null,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint resource_grant_version_fk foreign key (resource_version_id, resource_id)
        references resource_version (id, resource_id),
    constraint resource_grant_grantee check (
        (grantee_type = 'user' and grantee_user_sub is not null and grantee_organization_id is null
            and mode is null)
        or (grantee_type = 'organization' and grantee_organization_id is not null and grantee_user_sub is null
            and mode in ('temporary', 'long_term'))),
    constraint resource_grant_temporary_expiry check (mode <> 'temporary' or expires_at is not null)
);

-- Bản nhận licensed trỏ tới grant cấp phép của nó; grant nằm ở phân vùng của trung tâm nhận, cùng phân vùng với
-- bản nhận, nên khoá ngoại này kiểm được trọn vẹn trong database.
alter table resource
    add constraint resource_license_grant_fk foreign key (license_grant_id) references resource_grant (id);

create table collection_assignment (
    id uuid primary key,
    organization_id varchar(255) not null,
    partition_key varchar(255) not null,
    collection_id uuid not null,
    resource_type varchar(255) not null,
    assignee_sub varchar(255) not null,
    assignment_role varchar(64) not null,
    assigned_by_sub varchar(255) not null,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint collection_assignment_collection_fk foreign key (collection_id, partition_key)
        references resource (id, partition_key)
);

create table resource_dependency (
    id uuid primary key,
    resource_id uuid not null,
    resource_version_id uuid not null,
    depends_on_resource_id uuid not null,
    depends_on_version_id uuid not null,
    partition_key varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint resource_dependency_version_fk foreign key (resource_version_id, resource_id)
        references resource_version (id, resource_id),
    constraint resource_dependency_depends_on_fk foreign key (depends_on_version_id, depends_on_resource_id)
        references resource_version (id, resource_id)
);

create table handover_request (
    id uuid primary key,
    organization_id varchar(255) not null,
    partition_key varchar(255) not null,
    requested_teacher_sub varchar(255) not null,
    resource_types text,
    destination_collection_id uuid,
    reuse_rights text,
    status varchar(32) not null,
    created_by_sub varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint handover_request_status check (status in ('open', 'closed')),
    constraint handover_request_destination_fk foreign key (destination_collection_id, partition_key)
        references resource (id, partition_key)
);

create table submission (
    id uuid primary key,
    handover_request_id uuid not null references handover_request (id),
    partition_key varchar(255) not null,
    submitter_sub varchar(255) not null,
    submitted_at timestamp with time zone not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create table submission_item (
    id uuid primary key,
    submission_id uuid not null references submission (id),
    partition_key varchar(255) not null,
    source_resource_id uuid not null,
    source_version_id uuid not null,
    received_resource_id uuid not null,
    received_version_id uuid not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);
