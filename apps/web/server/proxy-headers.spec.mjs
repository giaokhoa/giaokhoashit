import { describe, expect, it } from 'vitest';
import { buildProxyHeaders, buildUpgradeRequestLines, organizationAliasForHostname, withOrganizationAliasHeader } from './proxy-headers.mjs';

/**
 * Hồi quy header của đường proxy HTTP trong prod-server.mjs
 * (topology C, tiền điều kiện TĐ5 của issue #587).
 *
 * Bối cảnh production: tầng ứng dụng phân giải trung tâm theo nhãn đầu tiên của header Host (issue #582) — tại địa chỉ
 * của một trung tâm ở kho riêng, handler đăng nhập Better Auth chỉ kiểm chứng credential ở kho của trung tâm đó khi
 * Host tới API còn nguyên là tên miền của trung tâm. prod-server bản cũ ghi đè Host bằng origin nội bộ của API
 * (`api:3100`) nên API không bao giờ thấy địa chỉ trung tâm: tài khoản chỉ sống ở kho riêng đăng nhập tại chính địa chỉ
 * của trung tâm bị từ chối, còn tài khoản của kho chung lại đăng nhập thành công tại địa chỉ đó (kiểm chứng tại thí
 * điểm nhantin ngày 01/11/2026). Spec này ghim: header dựng cho request phía API phải giữ nguyên Host gốc, đồng thời
 * giữ đúng hành vi nối chuỗi X-Forwarded-For và mặc định X-Forwarded-Proto như trước.
 *
 * Cùng bất biến đó áp cho đường hầm WebSocket upgrade (điểm B1 của PR #634): client của hệ cấu hình `transports:
 * ['websocket', 'polling']` nên handshake của socket.io là một request upgrade WebSocket ngay từ đầu, và socket auth ở
 * API phân giải trung tâm theo Host của chính request bắt tay đó. `proxyUpgrade` bản cũ cũng ghi đè Host bằng origin
 * nội bộ trong đầu request thô, nên phiên của trung tâm kho riêng bị tra ở kho chung còn phiên kho chung lại được chấp
 * nhận tại địa chỉ trung tâm. Khối describe thứ hai ghim đầu request upgrade mà đường hầm ghi về API: Host giữ nguyên
 * giá trị gốc, X-Forwarded-For và mọi header còn lại giữ nguyên hành vi hiện hành.
 */
describe('buildProxyHeaders', () => {
  it('giữ nguyên Host của request gốc để địa chỉ trung tâm kho riêng tới được tầng phân giải của API', () => {
    const headers = buildProxyHeaders(
      { host: 'nhantin.fuckr.net', origin: 'https://nhantin.fuckr.net' },
      '203.0.113.7',
      'api:3100',
    );
    expect(headers.host).toBe('nhantin.fuckr.net');
  });

  it('không ghi đè Host bằng origin nội bộ của API kể cả khi request mang cổng công khai', () => {
    const headers = buildProxyHeaders({ host: 'trung-tam-a.example.com:8443' }, '203.0.113.7', 'api:3100');
    expect(headers.host).toBe('trung-tam-a.example.com:8443');
    expect(headers.host).not.toBe('api:3100');
  });

  it('dùng origin nội bộ làm Host dự phòng khi request gốc không mang Host', () => {
    const headers = buildProxyHeaders({}, '203.0.113.7', 'api:3100');
    expect(headers.host).toBe('api:3100');
  });

  it('nối IP của chặng proxy này vào cuối chuỗi X-Forwarded-For đã có', () => {
    const headers = buildProxyHeaders(
      { host: 'dev.fuckr.net', 'x-forwarded-for': '198.51.100.1, 198.51.100.2' },
      '203.0.113.7',
      'api:3100',
    );
    expect(headers['x-forwarded-for']).toBe('198.51.100.1, 198.51.100.2, 203.0.113.7');
  });

  it('tạo X-Forwarded-For từ IP của chặng này khi request chưa có chuỗi', () => {
    const headers = buildProxyHeaders({ host: 'dev.fuckr.net' }, '203.0.113.7', 'api:3100');
    expect(headers['x-forwarded-for']).toBe('203.0.113.7');
  });

  it('đặt X-Forwarded-Proto mặc định là http khi request chưa mang header này', () => {
    const headers = buildProxyHeaders({ host: 'dev.fuckr.net' }, '203.0.113.7', 'api:3100');
    expect(headers['x-forwarded-proto']).toBe('http');
  });

  it('giữ nguyên X-Forwarded-Proto mà chặng trước đã đặt', () => {
    const headers = buildProxyHeaders(
      { host: 'nhantin.fuckr.net', 'x-forwarded-proto': 'https' },
      '203.0.113.7',
      'api:3100',
    );
    expect(headers['x-forwarded-proto']).toBe('https');
  });

  it('chuyển nguyên các header còn lại của request gốc (cookie, origin, content-type)', () => {
    const headers = buildProxyHeaders(
      {
        host: 'nhantin.fuckr.net',
        cookie: 'better-auth.session_token=abc',
        origin: 'https://nhantin.fuckr.net',
        'content-type': 'application/json',
      },
      '203.0.113.7',
      'api:3100',
    );
    expect(headers.cookie).toBe('better-auth.session_token=abc');
    expect(headers.origin).toBe('https://nhantin.fuckr.net');
    expect(headers['content-type']).toBe('application/json');
  });
});

describe('buildUpgradeRequestLines', () => {
  it('giữ nguyên Host gốc của client trong dòng Host của đầu request upgrade để socket auth phân giải đúng trung tâm kho riêng', () => {
    const lines = buildUpgradeRequestLines({
      method: 'GET',
      url: '/socket.io/?EIO=4&transport=websocket',
      httpVersion: '1.1',
      rawHeaders: ['Host', 'nhantin.fuckr.net', 'Connection', 'Upgrade', 'Upgrade', 'websocket'],
      headers: { host: 'nhantin.fuckr.net', connection: 'Upgrade', upgrade: 'websocket' },
      remoteAddress: '203.0.113.7',
      apiHost: 'api:3100',
    });
    expect(lines).toContain('Host: nhantin.fuckr.net');
    expect(lines).not.toContain('Host: api:3100');
  });

  it('giữ nguyên cả cổng công khai trong Host của request upgrade, không thay bằng cổng của origin nội bộ', () => {
    const lines = buildUpgradeRequestLines({
      method: 'GET',
      url: '/socket.io/?EIO=4&transport=websocket',
      httpVersion: '1.1',
      rawHeaders: ['Host', 'trung-tam-a.example.com:8443'],
      headers: { host: 'trung-tam-a.example.com:8443' },
      remoteAddress: '203.0.113.7',
      apiHost: 'api:3100',
    });
    expect(lines[1]).toBe('Host: trung-tam-a.example.com:8443');
  });

  it('dùng origin nội bộ làm dòng Host dự phòng khi request upgrade hoàn toàn không mang Host', () => {
    const lines = buildUpgradeRequestLines({
      method: 'GET',
      url: '/socket.io/?EIO=4&transport=websocket',
      httpVersion: '1.1',
      rawHeaders: ['Connection', 'Upgrade', 'Upgrade', 'websocket'],
      headers: { connection: 'Upgrade', upgrade: 'websocket' },
      remoteAddress: '203.0.113.7',
      apiHost: 'api:3100',
    });
    expect(lines).toContain('Host: api:3100');
  });

  it('nối IP của chặng này vào cuối chuỗi X-Forwarded-For đã có ngay tại dòng header đó và không thêm dòng thứ hai', () => {
    const lines = buildUpgradeRequestLines({
      method: 'GET',
      url: '/socket.io/?EIO=4&transport=websocket',
      httpVersion: '1.1',
      rawHeaders: ['Host', 'nhantin.fuckr.net', 'X-Forwarded-For', '198.51.100.1, 198.51.100.2'],
      headers: { host: 'nhantin.fuckr.net', 'x-forwarded-for': '198.51.100.1, 198.51.100.2' },
      remoteAddress: '203.0.113.7',
      apiHost: 'api:3100',
    });
    expect(lines.filter((line) => line.toLowerCase().startsWith('x-forwarded-for:'))).toEqual([
      'X-Forwarded-For: 198.51.100.1, 198.51.100.2, 203.0.113.7',
    ]);
  });

  it('thêm một dòng X-Forwarded-For từ IP của chặng này khi request upgrade chưa mang header này', () => {
    const lines = buildUpgradeRequestLines({
      method: 'GET',
      url: '/socket.io/?EIO=4&transport=websocket',
      httpVersion: '1.1',
      rawHeaders: ['Host', 'nhantin.fuckr.net'],
      headers: { host: 'nhantin.fuckr.net' },
      remoteAddress: '203.0.113.7',
      apiHost: 'api:3100',
    });
    expect(lines[lines.length - 1]).toBe('X-Forwarded-For: 203.0.113.7');
  });

  it('dựng trọn đầu request upgrade theo đúng thứ tự: dòng request đầu tiên rồi các header chuyển nguyên văn', () => {
    const lines = buildUpgradeRequestLines({
      method: 'GET',
      url: '/socket.io/?EIO=4&transport=websocket',
      httpVersion: '1.1',
      rawHeaders: [
        'Host',
        'nhantin.fuckr.net',
        'Connection',
        'Upgrade',
        'Upgrade',
        'websocket',
        'Sec-WebSocket-Key',
        'dGhlIHNhbXBsZSBub25jZQ==',
        'Sec-WebSocket-Version',
        '13',
        'Cookie',
        'better-auth.session_token=token-phien',
      ],
      headers: {
        host: 'nhantin.fuckr.net',
        connection: 'Upgrade',
        upgrade: 'websocket',
        'sec-websocket-key': 'dGhlIHNhbXBsZSBub25jZQ==',
        'sec-websocket-version': '13',
        cookie: 'better-auth.session_token=token-phien',
      },
      remoteAddress: '203.0.113.7',
      apiHost: 'api:3100',
    });
    expect(lines).toEqual([
      'GET /socket.io/?EIO=4&transport=websocket HTTP/1.1',
      'Host: nhantin.fuckr.net',
      'Connection: Upgrade',
      'Upgrade: websocket',
      'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==',
      'Sec-WebSocket-Version: 13',
      'Cookie: better-auth.session_token=token-phien',
      'X-Forwarded-For: 203.0.113.7',
    ]);
  });
});

/**
 * Header ngữ cảnh trang cho apps/server (issue #57): server phân giải ngữ cảnh trung tâm từ header
 * `X-LMS-Organization-Alias` mà chỉ tầng web này mới đặt được, suy từ Host của request. Khối này ghim: hostname
 * trung tâm cho đúng alias (kể cả khi Host kèm cổng), hostname nền tảng và Host thiếu cho null, và giá trị do
 * client tự gửi không bao giờ được chuyển tiếp — ở trang nền tảng header bị xoá hẳn, ở trang trung tâm header bị
 * ghi đè bằng alias đúng của trang.
 */
describe('organizationAliasForHostname / withOrganizationAliasHeader', () => {
  it('suy đúng alias từ Host của hai trang trung tâm, kể cả khi Host kèm cổng', () => {
    expect(organizationAliasForHostname('trung-tam-1.fuckr.net')).toBe('trung-tam-1');
    expect(organizationAliasForHostname('trung-tam-2.fuckr.net')).toBe('trung-tam-2');
    expect(organizationAliasForHostname('trung-tam-1.fuckr.net:8443')).toBe('trung-tam-1');
  });

  it('trang nền tảng và request không mang Host không có alias', () => {
    expect(organizationAliasForHostname('giaokhoa.fuckr.net')).toBeNull();
    expect(organizationAliasForHostname(undefined)).toBeNull();
    expect(organizationAliasForHostname('')).toBeNull();
  });

  it('đặt header alias đúng của trang, ghi đè giá trị client tự gửi', () => {
    const headers = withOrganizationAliasHeader({ 'x-lms-organization-alias': 'trung-tam-2' }, 'trung-tam-1.fuckr.net');
    expect(headers['x-lms-organization-alias']).toBe('trung-tam-1');
  });

  it('xoá hẳn header ở trang nền tảng để giá trị client tự gửi không lọt về server', () => {
    const headers = withOrganizationAliasHeader({ 'x-lms-organization-alias': 'trung-tam-1' }, 'giaokhoa.fuckr.net');
    expect('x-lms-organization-alias' in headers).toBe(false);
  });
});
