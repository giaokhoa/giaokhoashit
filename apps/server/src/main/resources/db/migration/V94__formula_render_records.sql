-- Bản ghi render của các khối công thức: mỗi ảnh công thức sinh ra từ một revision cụ thể của nguồn, kèm
-- toàn bộ cấu hình render và phiên bản bộ render đã sinh ảnh, để ảnh của một phiên bản đã chốt không bị
-- render lại âm thầm khi bộ render nâng cấp. Cột image_png là nơi giữ byte ảnh cho tới khi nền tệp (bảng
-- blob và cổng lưu trữ của lát nền) sẵn sàng: khi tích hợp, byte chuyển vào blob, cột image_blob_id được
-- điền và image_png được dọn; mọi cột còn lại của bản ghi giữ nguyên. Role runtime được cấp quyền dữ kiện
-- trên các bảng này qua default privileges mà migration V3 đã đặt cho mọi bảng tạo sau.
create table formula_block (
    id uuid primary key,
    question_version_id uuid not null references question_version (id),
    owner_sub varchar(255) not null,
    section varchar(32) not null,
    block_id varchar(255) not null,
    position integer not null,
    form varchar(16) not null,
    display boolean not null default false,
    system_origin boolean not null default false,
    source text,
    description text,
    revision integer not null default 0,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (question_version_id, block_id)
);

create table formula_render (
    id uuid primary key,
    formula_block_id uuid not null references formula_block (id),
    source_revision integer not null,
    source_hash varchar(64) not null,
    status varchar(16) not null,
    image_blob_id uuid,
    image_png bytea,
    image_width integer,
    image_height integer,
    baseline_offset integer,
    renderer_version varchar(128) not null,
    render_config text not null,
    error_code varchar(64),
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (formula_block_id, source_revision)
);
