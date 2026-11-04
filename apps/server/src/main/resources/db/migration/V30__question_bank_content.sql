-- Bảng nội dung chuyên biệt của feat câu hỏi: dữ kiện cá nhân gắn với tài khoản của chủ sở hữu qua owner_sub,
-- không gắn với trang đang mở — trang của một trung tâm không đổi phạm vi của dữ kiện cá nhân. Role runtime
-- được cấp quyền dữ kiện trên các bảng này qua default privileges mà migration V3 đã đặt cho mọi bảng tạo sau;
-- lớp Row Level Security riêng của các bảng này nằm ở migration V31 phía PostgreSQL.
create table question_bank (
    id uuid primary key,
    owner_sub varchar(255) not null,
    name varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create table question (
    id uuid primary key,
    owner_sub varchar(255) not null,
    bank_id uuid not null references question_bank (id),
    source_question_id uuid,
    source_version_id uuid,
    usage_limits text,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create table question_version (
    id uuid primary key,
    question_id uuid not null references question (id),
    owner_sub varchar(255) not null,
    version_number integer not null,
    status varchar(32) not null,
    locked_at timestamp with time zone,
    question_type varchar(32) not null,
    content_contract_version integer not null,
    stem text not null,
    choices text,
    correct_choice_id varchar(255),
    solution text,
    rubric text,
    content_hash varchar(64) not null,
    revision integer not null default 0,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (question_id, version_number)
);

create table bank_entry (
    id uuid primary key,
    bank_id uuid not null references question_bank (id),
    question_id uuid not null,
    question_version_id uuid not null references question_version (id),
    owner_sub varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    unique (bank_id, question_version_id)
);
