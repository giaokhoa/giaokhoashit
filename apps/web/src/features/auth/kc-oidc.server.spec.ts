import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Kiểm thử module OIDC phía server của luồng đăng nhập Keycloak
 * (issue #38): kênh cookie chống cookie-tossing (tiền tố `__Host-` và
 * từ chối cookie trùng tên), đối chiếu issuer trong tài liệu discovery,
 * ma trận kiểm các claim bắt buộc của ID token, và vòng đời của cookie
 * chờ cùng phiên phía web. `fetch` được mock tại biên; module được nạp
 * lại cho từng test để cache discovery và kho phiên không rò giữa test.
 */

const ISSUER = 'https://kc.example.test/realms/giaokhoa';
const CLIENT_ID = 'lms-web';
const OIDC_COOKIE_NAME = '__Host-lms_kc_oidc';

const DISCOVERY_DOC = {
  issuer: ISSUER,
  authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
  token_endpoint: `${ISSUER}/protocol/openid-connect/token`,
  end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
};

let mod: typeof import('./kc-oidc.server');

beforeEach(async () => {
  vi.resetModules();
  vi.stubEnv('KEYCLOAK_ISSUER', ISSUER);
  vi.stubEnv('KEYCLOAK_CLIENT_ID', CLIENT_ID);
  mod = await import('./kc-oidc.server');
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

/** Dựng request mang đúng một header Cookie cho trước. */
function requestWithCookies(cookieHeader: string): Request {
  return new Request('https://trung-tam-1.fuckr.net/tenant/callback', { headers: { cookie: cookieHeader } });
}

/** Lấy cặp `tên=giá trị` đầu của một header Set-Cookie để mang vào request sau. */
function cookiePairOf(setCookie: string): string {
  return setCookie.split(';')[0]!;
}

/** Dựng một ID token giả (chữ ký không được kiểm ở phía web) từ payload cho trước. */
function fakeIdToken(payload: Record<string, unknown>): string {
  const encode = (value: unknown) => Buffer.from(JSON.stringify(value), 'utf8').toString('base64url');
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(payload)}.signature`;
}

/** Payload ID token đạt mọi claim bắt buộc, cho phép ghi đè từng claim để làm ca sai. */
function validPayload(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    iss: ISSUER,
    aud: CLIENT_ID,
    exp: Math.floor(Date.now() / 1000) + 300,
    nonce: 'nonce-1',
    ...overrides,
  };
}

describe('cookie của luồng OIDC', () => {
  it('cookie chờ mang tiền tố __Host-, luôn Secure, Path=/, HttpOnly, SameSite=Lax và không có Domain', () => {
    const setCookie = mod.pendingLoginCookie({ verifier: 'v', state: 's', nonce: 'n' });
    expect(setCookie.startsWith(`${OIDC_COOKIE_NAME}=`)).toBe(true);
    expect(setCookie).toContain('; Secure');
    expect(setCookie).toContain('Path=/');
    expect(setCookie).toContain('HttpOnly');
    expect(setCookie).toContain('SameSite=Lax');
    expect(setCookie).toContain('Max-Age=600');
    expect(setCookie).not.toContain('Domain=');
  });

  it('cookie phiên mang tiền tố __Host-, luôn Secure và không có Domain', () => {
    const setCookie = mod.createSessionCookie({ accessToken: 'a', idToken: 'i', expiresInSeconds: 300 });
    expect(setCookie.startsWith(`${mod.KC_SESSION_COOKIE}=`)).toBe(true);
    expect(mod.KC_SESSION_COOKIE.startsWith('__Host-')).toBe(true);
    expect(setCookie).toContain('; Secure');
    expect(setCookie).not.toContain('Domain=');
  });

  it('cookie xoá vẫn giữ tiền tố __Host- và Secure kèm Max-Age=0', () => {
    expect(mod.clearPendingLoginCookie()).toContain('Max-Age=0');
    expect(mod.clearPendingLoginCookie()).toContain('; Secure');
    const clearSession = mod.destroySessionCookie(requestWithCookies(''));
    expect(clearSession.startsWith(`${mod.KC_SESSION_COOKIE}=`)).toBe(true);
    expect(clearSession).toContain('Max-Age=0');
  });
});

describe('vòng đời cookie chờ callback', () => {
  it('đọc lại đúng bộ ba PKCE/state/nonce đã gói vào cookie chờ', () => {
    const pending = { verifier: 'verifier-1', state: 'state-1', nonce: 'nonce-1' };
    const request = requestWithCookies(cookiePairOf(mod.pendingLoginCookie(pending)));
    expect(mod.readPendingLogin(request)).toEqual(pending);
  });

  it('trả null khi cookie chờ không phải JSON hợp lệ hoặc thiếu một trường của bộ ba', () => {
    expect(mod.readPendingLogin(requestWithCookies(`${OIDC_COOKIE_NAME}=khong-phai-json`))).toBeNull();
    const incomplete = Buffer.from(JSON.stringify({ verifier: 'v', state: 's' }), 'utf8').toString('base64url');
    expect(mod.readPendingLogin(requestWithCookies(`${OIDC_COOKIE_NAME}=${incomplete}`))).toBeNull();
  });

  it('từ chối khi tên cookie chờ xuất hiện hai lần trong header (ca cookie-tossing)', () => {
    const first = cookiePairOf(mod.pendingLoginCookie({ verifier: 'v1', state: 's1', nonce: 'n1' }));
    const second = cookiePairOf(mod.pendingLoginCookie({ verifier: 'v2', state: 's2', nonce: 'n2' }));
    expect(mod.readPendingLogin(requestWithCookies(`${second}; ${first}`))).toBeNull();
  });
});

describe('phiên phía web', () => {
  it('đọc lại phiên còn hiệu lực từ cookie phiên', () => {
    const setCookie = mod.createSessionCookie({ accessToken: 'access-1', idToken: 'id-1', expiresInSeconds: 300 });
    const session = mod.readSession(requestWithCookies(cookiePairOf(setCookie)));
    expect(session?.accessToken).toBe('access-1');
    expect(session?.idToken).toBe('id-1');
  });

  it('coi phiên đã hết hạn theo tuổi access token là chưa đăng nhập', () => {
    const setCookie = mod.createSessionCookie({ accessToken: 'a', idToken: 'i', expiresInSeconds: -1 });
    expect(mod.readSession(requestWithCookies(cookiePairOf(setCookie)))).toBeNull();
  });

  it('huỷ phiên khi đăng xuất: đọc lại sau đó trả null', () => {
    const setCookie = mod.createSessionCookie({ accessToken: 'a', idToken: 'i', expiresInSeconds: 300 });
    const pair = cookiePairOf(setCookie);
    mod.destroySessionCookie(requestWithCookies(pair));
    expect(mod.readSession(requestWithCookies(pair))).toBeNull();
  });

  it('từ chối khi tên cookie phiên xuất hiện hai lần trong header', () => {
    const setCookie = mod.createSessionCookie({ accessToken: 'a', idToken: 'i', expiresInSeconds: 300 });
    const pair = cookiePairOf(setCookie);
    expect(mod.readSession(requestWithCookies(`${pair}; ${pair}`))).toBeNull();
  });
});

describe('getOidcDiscovery', () => {
  it('trả các endpoint và issuer khi tài liệu discovery khớp issuer đã cấu hình', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, status: 200, json: async () => DISCOVERY_DOC })),
    );
    const discovery = await mod.getOidcDiscovery();
    expect(discovery.issuer).toBe(ISSUER);
    expect(discovery.authorizationEndpoint).toBe(DISCOVERY_DOC.authorization_endpoint);
    expect(discovery.tokenEndpoint).toBe(DISCOVERY_DOC.token_endpoint);
    expect(discovery.endSessionEndpoint).toBe(DISCOVERY_DOC.end_session_endpoint);
  });

  it('từ chối tài liệu có issuer lệch và không nhớ đáp ứng lỗi vào cache', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ ...DISCOVERY_DOC, issuer: 'https://evil.example.test/realms/giaokhoa' }),
      })
      .mockResolvedValue({ ok: true, status: 200, json: async () => DISCOVERY_DOC });
    vi.stubGlobal('fetch', fetchMock);
    await expect(mod.getOidcDiscovery()).rejects.toThrow();
    const discovery = await mod.getOidcDiscovery();
    expect(discovery.issuer).toBe(ISSUER);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('từ chối tài liệu thiếu endpoint bắt buộc', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => ({ issuer: ISSUER, authorization_endpoint: DISCOVERY_DOC.authorization_endpoint }),
      })),
    );
    await expect(mod.getOidcDiscovery()).rejects.toThrow();
  });
});

describe('validateIdToken', () => {
  const expected = { issuer: ISSUER, clientId: CLIENT_ID, nonce: 'nonce-1' };

  it('đạt khi iss, aud, exp và nonce đều đúng', () => {
    expect(mod.validateIdToken(fakeIdToken(validPayload()), expected)).toBe(true);
  });

  it('đạt khi aud là mảng chứa client của luồng', () => {
    expect(mod.validateIdToken(fakeIdToken(validPayload({ aud: ['lms-api', CLIENT_ID] })), expected)).toBe(true);
  });

  it('từ chối khi iss không khớp issuer đã cấu hình', () => {
    const token = fakeIdToken(validPayload({ iss: 'https://evil.example.test/realms/giaokhoa' }));
    expect(mod.validateIdToken(token, expected)).toBe(false);
  });

  it('từ chối khi aud không chứa client của luồng', () => {
    expect(mod.validateIdToken(fakeIdToken(validPayload({ aud: 'lms-api' })), expected)).toBe(false);
    expect(mod.validateIdToken(fakeIdToken(validPayload({ aud: ['lms-api'] })), expected)).toBe(false);
  });

  it('từ chối khi exp đã qua', () => {
    const token = fakeIdToken(validPayload({ exp: Math.floor(Date.now() / 1000) - 60 }));
    expect(mod.validateIdToken(token, expected)).toBe(false);
  });

  it('từ chối khi nonce không khớp giá trị đã lưu', () => {
    expect(mod.validateIdToken(fakeIdToken(validPayload({ nonce: 'nonce-khac' })), expected)).toBe(false);
  });

  it('từ chối khi token không đủ ba phần hoặc payload không phải JSON', () => {
    expect(mod.validateIdToken('chi-hai.phan', expected)).toBe(false);
    const badPayload = `${Buffer.from('{}').toString('base64url')}.${Buffer.from('khong-json').toString('base64url')}.sig`;
    expect(mod.validateIdToken(badPayload, expected)).toBe(false);
  });
});
