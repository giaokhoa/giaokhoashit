import { describe, expect, it } from 'vitest';
import { isProxyPath, isServerProxyPath } from './proxy-paths.mjs';

/**
 * Hồi quy bộ khớp đường proxy của prod-server.mjs (topology C).
 *
 * Bối cảnh: MCP-0 (issue #110) mount `/mcp` + `/.well-known/oauth-protected-resource` NGOÀI `/api/v1`, nhưng
 * isProxyPath bản cũ chỉ khớp `/api/*` + `/socket.io/*` → POST /mcp trên production rơi vào SSR, trả 404 kèm HTML
 * (probe live v99). Spec này ghim: đường của API phải qua proxy, đường của SSR và các đường gần giống KHÔNG được qua.
 * Thêm bối cảnh vòng sửa PR #46 (issue #38): endpoint danh tính `/api/me` do `apps/server` phục vụ nhưng mọi
 * `/api/*` từng rơi về upstream API duy nhất → luật upstream riêng `isServerProxyPath` phải tách đúng đường này ra.
 */
describe('isProxyPath', () => {
  it('chuyển tiếp endpoint MCP (exact, subpath, kèm query)', () => {
    expect(isProxyPath('/mcp')).toBe(true);
    expect(isProxyPath('/mcp/')).toBe(true);
    expect(isProxyPath('/mcp/bat-ky')).toBe(true);
    expect(isProxyPath('/mcp?session=abc')).toBe(true);
  });

  it('chuyển tiếp PRM của MCP, gồm biến thể gắn tên resource', () => {
    expect(isProxyPath('/.well-known/oauth-protected-resource')).toBe(true);
    expect(isProxyPath('/.well-known/oauth-protected-resource/mcp')).toBe(true);
    expect(isProxyPath('/.well-known/oauth-protected-resource?x=1')).toBe(true);
  });

  it('chuyển tiếp discovery AS (Refs #7): dạng gốc, dạng chèn-đường-dẫn, kèm query', () => {
    expect(isProxyPath('/.well-known/oauth-authorization-server')).toBe(true);
    expect(isProxyPath('/.well-known/oauth-authorization-server/api/v1/auth')).toBe(true);
    expect(isProxyPath('/.well-known/oauth-authorization-server?x=1')).toBe(true);
    expect(isProxyPath('/.well-known/oauth-authorization-server/api/v1/auth?x=1')).toBe(true);
  });

  it('giữ nguyên các đường proxy cũ: /api và /socket.io', () => {
    expect(isProxyPath('/api')).toBe(true);
    expect(isProxyPath('/api/v1/courses')).toBe(true);
    expect(isProxyPath('/api/v1/courses?page=2')).toBe(true);
    expect(isProxyPath('/socket.io/?EIO=4&transport=polling')).toBe(true);
  });

  it('không nuốt đường SSR thường', () => {
    expect(isProxyPath('/')).toBe(false);
    expect(isProxyPath('/courses')).toBe(false);
    expect(isProxyPath('/courses/123/learn')).toBe(false);
    expect(isProxyPath('/login')).toBe(false);
  });

  it('không nuốt các đường gần giống', () => {
    expect(isProxyPath('/mcpfoo')).toBe(false);
    expect(isProxyPath('/apixyz')).toBe(false);
    expect(isProxyPath('/.well-known/other')).toBe(false);
    expect(isProxyPath('/.well-known/oauth-protected-resourceXYZ')).toBe(false);
    expect(isProxyPath('/.well-known/oauth-authorization-serverXYZ')).toBe(false);
    expect(isProxyPath('/dashboard/mcp')).toBe(false);
  });
});

describe('isServerProxyPath (upstream riêng của /api/me)', () => {
  it('đúng đường /api/me đi upstream server, kể cả khi kèm query string', () => {
    expect(isProxyPath('/api/me')).toBe(true);
    expect(isServerProxyPath('/api/me')).toBe(true);
    expect(isServerProxyPath('/api/me?x=1')).toBe(true);
  });

  it('mọi đường /api còn lại vẫn đi upstream api cũ', () => {
    expect(isServerProxyPath('/api')).toBe(false);
    expect(isServerProxyPath('/api/v1/courses')).toBe(false);
    expect(isServerProxyPath('/api/v1/courses?page=2')).toBe(false);
  });

  it('không mở tiền tố: subpath của /api/me và đường gần giống vẫn đi upstream api cũ', () => {
    expect(isServerProxyPath('/api/me/extra')).toBe(false);
    expect(isServerProxyPath('/api/me2')).toBe(false);
  });

  it('họ đường kiểm chứng khung tenancy đi upstream server (issue #57)', () => {
    expect(isProxyPath('/api/tenancy-check/context')).toBe(true);
    expect(isServerProxyPath('/api/tenancy-check/context')).toBe(true);
    expect(isServerProxyPath('/api/tenancy-check/records')).toBe(true);
    expect(isServerProxyPath('/api/tenancy-check/records?x=1')).toBe(true);
  });

  it('đường gần giống họ tenancy-check vẫn đi upstream api cũ', () => {
    expect(isServerProxyPath('/api/tenancy-checkfoo')).toBe(false);
    expect(isServerProxyPath('/api/tenancy-check')).toBe(false);
  });
});
