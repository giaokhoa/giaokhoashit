import { useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { MESSAGING_MAX_MESSAGE_LENGTH_DEFAULT } from '@lms/contracts';
import { ApiError } from '#lib/api';
import { useSendMessage } from '../queries/messaging';
import type { MessagingRealtimeHandle } from '../api/messaging-socket';
import { countMessageCharacters, localQuoteOf } from '../utils/utils';
import type { ConversationView, MessageQuoteView, MessageView } from '../types/types';

export interface UseMessageComposerParams {
  active: ConversationView | null;
  myId: string | undefined;
  addLocalMessage: (message: MessageView) => void;
  setLocalMessageStatus: (conversationId: string, clientMessageId: string, status: 'sending' | 'failed') => void;
  mergeServerMessages: (conversationId: string, messages: MessageView[]) => void;
  lastSeqRef: { current: Map<string, number> };
  realtimeRef: { current: MessagingRealtimeHandle | null };
}

export function useMessageComposer({
  active,
  myId,
  addLocalMessage,
  setLocalMessageStatus,
  mergeServerMessages,
  lastSeqRef,
  realtimeRef,
}: UseMessageComposerParams) {
  const { t } = useTranslation('app');
  /**
   * Nháp soạn tin THEO TỪNG conversation (lát M1, issue #204): map theo conversationId, giữ trong bộ nhớ trang — chuyển
   * qua conversation khác rồi quay lại vẫn còn nguyên nháp của conversation đó; gửi thành công thì chỉ xoá nháp của
   * đúng conversation đã gửi.
   */
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [replyTargets, setReplyTargets] = useState<Record<string, MessageView | null>>({});
  const [sendError, setSendError] = useState<string | null>(null);
  const sendMessageMutation = useSendMessage();
  const lastTypingSentRef = useRef(0);
  /**
   * Khoá idempotency của BẢN NHÁP hiện tại theo conversation (fix nhân đôi tin, issue #33): sinh một lần cho một bản
   * nháp và giữ nguyên qua mọi lượt submit lặp của cùng bản nháp — server chống trùng theo (conversation, người gửi,
   * clientMessageId) nên hai POST lặp mang CÙNG khoá chỉ thành một tin. Chỉ bỏ khoá khi lượt gửi đã có kết quả từ
   * server; lượt gửi lỗi thì tin failed tự giữ khoá của nó cho đường retrySend, bản nháp sau đó phải là khoá mới.
   */
  const draftClientIdRef = useRef<Map<string, string>>(new Map());
  /**
   * Conversation đang có lượt send() bay (fix nhân đôi tin, issue #33): lượt gọi trùng cho cùng conversation bị bỏ qua
   * ngay tại ref — không chờ setState nên hai submit trong cùng một nhịp render cũng không lọt cả hai.
   */
  const sendInFlightRef = useRef<Set<string>>(new Set());
  const [sendingConversationIds, setSendingConversationIds] = useState<ReadonlySet<string>>(new Set());

  async function deliver(
    conversationId: string,
    text: string,
    clientMessageId: string,
    quote?: { replyToMessageId: string; replyTo: MessageQuoteView } | null,
  ) {
    if (!myId) return;
    addLocalMessage({
      id: `temp-${clientMessageId}`,
      conversationId,
      userIdFrom: myId,
      fullMessage: text,
      createdAt: new Date().toISOString(),
      seq: 0,
      clientMessageId,
      replyToMessageId: quote?.replyToMessageId ?? null,
      replyTo: quote?.replyTo ?? null,
      localStatus: 'sending',
    });
    try {
      const saved = await sendMessageMutation.mutateAsync({
        conversationId,
        body: {
          text,
          clientMessageId,
          ...(quote ? { replyToMessageId: quote.replyToMessageId } : {}),
        },
      });
      const seen = lastSeqRef.current.get(conversationId) ?? 0;
      if (saved.seq > seen) lastSeqRef.current.set(conversationId, saved.seq);
      // Tin thật gộp vào cache chi tiết qua mergeServerMessages — cùng lượt đó bản tạm được dọn khỏi state lạc quan
      // theo clientMessageId (vòng đời bản tạm kết thúc tại xác nhận). Xem trước danh sách (tin cuối + đưa conversation
      // lên đầu — lát M1, issue #204) do chính đột biến gửi tin vá vào cache danh sách ở tầng dữ kiện, không chờ sự
      // kiện conversation.updated qua socket.
      mergeServerMessages(conversationId, [saved]);
    } catch (err) {
      if (err instanceof ApiError && err.problem.code === 'messaging.message_too_long') {
        setSendError(t('messageTooLong', { max: MESSAGING_MAX_MESSAGE_LENGTH_DEFAULT }));
      } else if (err instanceof ApiError && err.problem.code === 'messaging.rate_limited') {
        setSendError(t('messageRateLimited'));
      }
      setLocalMessageStatus(conversationId, clientMessageId, 'failed');
    }
  }

  async function send(event: FormEvent) {
    event.preventDefault();
    if (!active) return;
    // Khoá chống gọi trùng (fix nhân đôi tin, issue #33): một lượt send() đang bay cho conversation này thì lượt gọi
    // sau bị bỏ qua ngay tại ref — kể cả hai submit trong cùng một nhịp render, khi draft ở state chưa kịp xoá và cả
    // hai lượt đều đọc được nội dung.
    if (sendInFlightRef.current.has(active.id)) return;
    const text = drafts[active.id] ?? '';
    if (!text.trim()) return;
    // Áp trần ở client theo ĐÚNG cách đếm của server (lát M2): vượt trần thì giữ nguyên nháp + báo rõ, không bắn
    // request chắc chắn bị 400. Trần hiển thị là giá trị mặc định dùng chung ở contracts; server vẫn là nguồn sự thật
    // (quản trị đổi site-config thì lỗi 400 của server cũng được dịch rõ ở deliver).
    if (countMessageCharacters(text) > MESSAGING_MAX_MESSAGE_LENGTH_DEFAULT) {
      setSendError(t('messageTooLong', { max: MESSAGING_MAX_MESSAGE_LENGTH_DEFAULT }));
      return;
    }
    // Khoá idempotency của BẢN NHÁP này (fix nhân đôi tin, issue #33): đã có thì dùng lại, chưa có thì sinh một lần.
    // Mọi lượt submit lặp của cùng bản nháp mang CÙNG khoá → server trả đúng một tin kể cả khi cả hai POST đều tới nơi.
    let clientMessageId = draftClientIdRef.current.get(active.id);
    if (!clientMessageId) {
      clientMessageId = crypto.randomUUID();
      draftClientIdRef.current.set(active.id, clientMessageId);
    }
    sendInFlightRef.current.add(active.id);
    setSendingConversationIds((prev) => new Set(prev).add(active.id));
    try {
      setSendError(null);
      setDrafts((prev) => {
        const next = { ...prev };
        delete next[active.id];
        return next;
      });
      const target = replyTargets[active.id] ?? null;
      setReplyTargets((prev) => {
        const next = { ...prev };
        delete next[active.id];
        return next;
      });
      await deliver(
        active.id,
        text,
        clientMessageId,
        target ? { replyToMessageId: target.id, replyTo: localQuoteOf(target) } : null,
      );
      // Lượt gửi đã có kết quả từ server (tin thật về, hoặc tin lạc quan đã rơi vào failed và giữ khoá của chính nó cho
      // retrySend) — bản nháp hiện tại chấm dứt ở đây: bỏ khoá để bản nháp KẾ TIẾP sinh khoá mới, không bị gộp oan vào
      // tin vừa gửi.
      if (draftClientIdRef.current.get(active.id) === clientMessageId) {
        draftClientIdRef.current.delete(active.id);
      }
    } finally {
      sendInFlightRef.current.delete(active.id);
      setSendingConversationIds((prev) => {
        const next = new Set(prev);
        next.delete(active.id);
        return next;
      });
    }
  }

  /**
   * Thử gửi lại một tin đã lỗi — dùng lại đúng clientMessageId nên server không tạo trùng kể cả khi lần trước đã tới
   * nơi.
   */
  async function retrySend(message: MessageView) {
    if (!active || !message.clientMessageId) return;
    setLocalMessageStatus(active.id, message.clientMessageId, 'sending');
    await deliver(
      active.id,
      message.fullMessage,
      message.clientMessageId,
      message.replyToMessageId && message.replyTo
        ? { replyToMessageId: message.replyToMessageId, replyTo: message.replyTo }
        : null,
    );
  }

  function noteTyping() {
    if (!active) return;
    const now = Date.now();
    if (now - lastTypingSentRef.current < 3000) return;
    lastTypingSentRef.current = now;
    realtimeRef.current?.typing(active.id);
  }

  function resetComposerForOpen() {
    setSendError(null);
  }

  const draft = active ? (drafts[active.id] ?? '') : '';
  const replyTarget = active ? (replyTargets[active.id] ?? null) : null;

  return {
    drafts,
    setDrafts,
    replyTargets,
    setReplyTargets,
    sendError,
    sendingConversationIds,
    send,
    retrySend,
    noteTyping,
    resetComposerForOpen,
    draft,
    replyTarget,
  };
}
