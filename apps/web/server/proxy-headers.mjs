/**
 * Dựng header cho các request mà prod-server.mjs chuyển về API nội
 * bộ (topology C): bộ header của đường proxy HTTP và đầu request thô
 * của đường hầm WebSocket upgrade.
 *
 * Flow: request của trình duyệt tới mặt tiền công khai → prod-server nhận diện đường proxy (xem proxy-paths.mjs) → bộ
 * header dựng ở đây được gắn lên request phía API (node:http) → API xử lý theo đúng địa chỉ công khai mà trình duyệt đã
 * gọi. Đường hầm upgrade của socket.io không đi qua node:http: prod-server tự viết lại dòng request và từng dòng header
 * ra đường hầm TCP — phần viết lại đó cũng dựng ở đây để cùng chịu một bất biến địa chỉ.
 *
 * Bất biến địa chỉ: header `Host` của request gốc là địa chỉ công khai duy nhất mà tầng ứng dụng dùng để phân giải
 * trung tâm theo tên miền con (issue #582: nhãn đầu tiên của Host là slug của trung tâm trong sổ đăng ký). Đích TCP của
 * request phía API do hostname/port của origin nội bộ quyết định (tham số của httpRequest), KHÔNG do header Host quyết
 * — nên giữ nguyên Host gốc không làm lệch đích kết nối, còn ghi đè Host bằng origin nội bộ thì địa chỉ trung tâm biến
 * mất lặng lẽ trước khi tới API: mọi phân giải theo địa chỉ ở API (guard gắn ngữ cảnh, handler đăng nhập Better Auth,
 * socket auth) đều rơi về đường mặc định.
 *
 * Hai header chuyển tiếp còn lại giữ đúng hành vi hiện hành: X-Forwarded-For nối tiếp chuỗi của các chặng trước rồi
 * thêm IP của chặng này; X-Forwarded-Proto chỉ đặt mặc định `http` khi request chưa mang (chặng tunnel ở biên đã mang
 * thì giữ nguyên).
 */

function forwardedFor(prior, remoteAddress) {
  const clientIp = remoteAddress ?? '';
  return prior ? `${prior}, ${clientIp}` : clientIp;
}

/**
 * Ánh xạ hostname trung tâm → alias Organization của Keycloak, dùng để đặt header ngữ cảnh trang
 * `X-LMS-Organization-Alias` cho các request đi `apps/server` (issue #57). Phải khớp bảng hostname phía client
 * `src/lib/kc-tenant-hosts.ts` — hai bảng cùng liệt kê đúng hai hostname trung tâm hiện có; hostname không có
 * trong bảng là trang nền tảng (không ngữ cảnh trung tâm).
 */
const ORGANIZATION_ALIAS_BY_HOSTNAME = {
  'trung-tam-1.fuckr.net': 'trung-tam-1',
  'trung-tam-2.fuckr.net': 'trung-tam-2',
};

export function organizationAliasForHostname(host) {
  if (!host) return null;
  const hostname = host.split(':')[0].toLowerCase();
  return ORGANIZATION_ALIAS_BY_HOSTNAME[hostname] ?? null;
}

/**
 * Đặt header ngữ cảnh trang lên bộ header sẽ gửi về `apps/server`: hostname trung tâm thì đặt alias tương ứng,
 * hostname nền tảng thì XOÁ hẳn header. Việc xoá khi không có alias là bắt buộc về bảo mật: header này là dữ kiện
 * để server phân giải ngữ cảnh trung tâm, nên giá trị do client tự gửi không bao giờ được chuyển tiếp nguyên trạng
 * — chỉ giá trị suy từ Host của request (địa chỉ công khai mà trình duyệt đã gọi) mới được dùng.
 */
export function withOrganizationAliasHeader(headers, host) {
  const alias = organizationAliasForHostname(host);
  if (alias) headers['x-lms-organization-alias'] = alias;
  else delete headers['x-lms-organization-alias'];
  return headers;
}

export function buildProxyHeaders(incomingHeaders, remoteAddress, apiHost) {
  const headers = {
    ...incomingHeaders,
    // Host giữ nguyên của request gốc (xem bất biến địa chỉ ở đầu file): chỉ khi request không mang Host mới rơi về
    // origin nội bộ như hành vi cũ cho các request không địa chỉ (health check nội bộ...). TUYỆT ĐỐI không ghi đè Host
    // đã có — đích kết nối do hostname/port của httpRequest quyết, không do header này.
    host: incomingHeaders.host ?? apiHost,
    'x-forwarded-for': forwardedFor(incomingHeaders['x-forwarded-for'], remoteAddress),
  };
  if (!headers['x-forwarded-proto']) headers['x-forwarded-proto'] = 'http';
  return headers;
}

/**
 * Dựng các dòng của đầu request upgrade (WebSocket của socket.io) mà đường hầm TCP trong prod-server.mjs ghi thẳng về
 * API nội bộ.
 *
 * Bất biến địa chỉ ở đầu file áp cho cả đường này: dòng Host mang GIÁ TRỊ GỐC của client — socket auth ở API phân giải
 * trung tâm theo Host của chính request upgrade bắt tay (client của hệ mở transport websocket ngay từ request đầu
 * tiên), nên ghi đè Host bằng origin nội bộ thì phiên của trung tâm kho riêng bị tra ở kho chung còn phiên kho chung
 * lại được chấp nhận tại địa chỉ trung tâm đó (điểm B1 của PR #634). `apiHost` chỉ còn là dòng dự phòng khi request gốc
 * hoàn toàn không mang Host. Phần X-Forwarded-For giữ nguyên từng ký tự hành vi hiện hành của đường hầm: nối IP của
 * chặng này vào chuỗi đã có ngay tại dòng header đó, và thêm một dòng mới từ IP của chặng này khi request chưa mang
 * header này (điều kiện xét trên `headers` đã chuẩn hoá của node:http như cách đường hầm vẫn xét). Mọi header còn lại
 * chuyển nguyên văn, giữ nguyên thứ tự và tên như client đã gửi.
 *
 * @param method - Method của request gốc (req.method).
 * @param url - URL của request gốc (req.url, gồm cả query string của handshake socket.io).
 * @param httpVersion - Phiên bản HTTP của request gốc (req.httpVersion).
 * @param rawHeaders - Mảng header thô của request gốc (req.rawHeaders: tên, giá trị xen kẽ, giữ nguyên hoa thường và
                       thứ tự).
 * @param headers - Header đã chuẩn hoá của request gốc (req.headers, khoá chữ thường) — chỉ dùng để xét request đã mang
                    X-Forwarded-For hay chưa.
 * @param remoteAddress - IP của chặng kết nối tới prod-server (req.socket.remoteAddress).
 * @param apiHost - Host của origin API nội bộ (apiOrigin.host) — chỉ còn là giá trị dự phòng khi request gốc không mang
                    Host.
 * @returns Các dòng của đầu request (chưa kèm dòng trống kết thúc đầu request): dòng request đầu tiên rồi tới từng dòng
            header.
 */
export function buildUpgradeRequestLines({ method, url, httpVersion, rawHeaders, headers, remoteAddress, apiHost }) {
  const lines = [`${method} ${url} HTTP/${httpVersion}`];
  let hostSeen = false;
  for (let i = 0; i < rawHeaders.length; i += 2) {
    const name = rawHeaders[i];
    const lower = name.toLowerCase();
    if (lower === 'host') {
      // Host giữ nguyên giá trị gốc của client (xem bất biến địa chỉ ở đầu file) — TUYỆT ĐỐI không ghi đè bằng origin
      // nội bộ; đích của đường hầm do hostname/port của origin quyết ở điểm gọi netConnect, không do dòng header này.
      lines.push(`Host: ${rawHeaders[i + 1]}`);
      hostSeen = true;
    } else if (lower === 'x-forwarded-for') {
      lines.push(`${name}: ${rawHeaders[i + 1]}, ${remoteAddress ?? ''}`);
    } else {
      lines.push(`${name}: ${rawHeaders[i + 1]}`);
    }
  }
  if (!hostSeen) lines.push(`Host: ${apiHost}`);
  if (!headers['x-forwarded-for']) lines.push(`X-Forwarded-For: ${remoteAddress ?? ''}`);
  return lines;
}
