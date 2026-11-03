import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHash } from 'node:crypto';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

export const OBJECT_STORAGE = Symbol('OBJECT_STORAGE');

/** Port ObjectStorage (report §3.8): tầng vật lý được định địa chỉ theo nội dung — khoá =
 * `<2 chars>/<next 2 chars>/<sha256>` đúng như filedir của Moodle, ngoại trừ hash là SHA-256 (quyết định của dự án:
 * không tương thích legacy). Adapter cục bộ được giao ngay bây giờ; một adapter S3 có thể cài đặt cùng port này.
 */
export interface ObjectStorage {
  /**
   * Ghi nội dung theo key (không ghi trùng nếu đã tồn tại).
   */
  put(contentHash: string, data: Buffer): Promise<void>;
  /**
   * Đọc nội dung theo key; null nếu không có.
   */
  get(contentHash: string): Promise<Buffer>;
  delete(contentHash: string): Promise<void>;
  exists(contentHash: string): Promise<boolean>;
  /** Đường dẫn/khoá tuyệt đối cho một hash — phơi bày cho test và việc dọn file mồ côi. */
  keyFor(contentHash: string): string;
}

export function sha256Hex(data: Buffer): string { return createHash('sha256').update(data).digest('hex'); }
export function contentKey(contentHash: string): string { return `${contentHash.slice(0, 2)}/${contentHash.slice(2, 4)}/${contentHash}`; }

/** Năng lực tuỳ chọn của adapter: cấp một URL tải xuống trực tiếp dạng presigned trỏ thẳng tới chính kho lưu trữ bên
 * ngoài, thay vì proxy byte qua API phía sau một chữ ký HMAC. Adapter cục bộ không có năng lực này — việc cấp phát
 * fallback về HMAC cho nó.
 */
export interface PresignedDownloadStorage {
  presignGetUrl(contentHash: string, expiresInSeconds: number, opts?: { fileName?: string; mimeType?: string }): Promise<string>;
}

export function supportsPresignedDownloads(storage: ObjectStorage): storage is ObjectStorage & PresignedDownloadStorage {
  return typeof (storage as Partial<PresignedDownloadStorage>).presignGetUrl === 'function';
}

@Injectable()
/** Kho lưu trữ nội dung file trên đĩa local, khoá theo contentHash (hai ký tự đầu làm thư mục con). Interface chung cho
 * phép thay bằng object storage ngoài mà không đổi tầng service.
 */
export class LocalObjectStorage implements ObjectStorage {
  private readonly root: string;
  constructor(config: ConfigService) { this.root = config.get<string>('FILE_STORAGE_DIR') ?? './data/files'; }
  keyFor(contentHash: string): string { return join(this.root, contentKey(contentHash)); }
  async put(contentHash: string, data: Buffer): Promise<void> {
    const path = this.keyFor(contentHash);
    await mkdir(join(path, '..'), { recursive: true });
    await writeFile(path, data, { flag: 'wx' }).catch((err: NodeJS.ErrnoException) => {
      if (err.code !== 'EEXIST') throw err;
    });
  }
  async get(contentHash: string): Promise<Buffer> { return readFile(this.keyFor(contentHash)); }
  async delete(contentHash: string): Promise<void> { await rm(this.keyFor(contentHash), { force: true }); }
  async exists(contentHash: string): Promise<boolean> {
    try { await readFile(this.keyFor(contentHash)); return true; } catch { return false; }
  }
}
