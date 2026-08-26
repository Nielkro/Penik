import { listCalls } from '../api.js';
import { el, spinner, avatar, formatTime, formatDate } from './components.js';
import { navigate, getWS } from '../app.js';
import { callManager } from '../call.js';
import { OP } from '../ws.js';

export function formatCallDuration(seconds) {
  if (!seconds || seconds <= 0) return '';
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  if (m === 0) return `${s} сек`;
  return `${m} мин ${s > 0 ? `${s} сек` : ''}`;
}

export function formatCallDateTime(timestampMs) {
  const date = new Date(timestampMs);
  const now = new Date();
  const timeStr = formatTime(timestampMs);

  const isToday = date.toDateString() === now.toDateString();
  if (isToday) return `Сегодня, ${timeStr}`;

  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (date.toDateString() === yesterday.toDateString()) return `Вчера, ${timeStr}`;

  return `${formatDate(timestampMs)}, ${timeStr}`;
}

export async function renderCalls(container) {
  container.innerHTML = '';

  const header = el('div', { class: 'chatlist-header' },
    el('h2', { class: 'chatlist-title' }, 'Звонки')
  );

  const listEl = el('div', { class: 'chatlist-contacts', style: 'padding-top: 4px;' });
  const loadEl = el('div', { class: 'chat-loading', style: 'padding: 40px; text-align: center;' }, spinner());
  listEl.appendChild(loadEl);

  container.append(header, listEl);

  let calls = [];
  try {
    calls = await listCalls(50, 0);
  } catch (err) {
    console.warn('Failed to load calls history:', err);
    calls = [];
  }

  loadEl.remove();

  function renderItems() {
    listEl.innerHTML = '';
    if (!calls || calls.length === 0) {
      listEl.appendChild(el('div', {
        style: 'padding: 48px 24px; text-align: center; color: var(--text-muted); font-size: 14px; line-height: 1.6;'
      },
        el('div', { style: 'font-size: 36px; margin-bottom: 12px;' }, '📞'),
        el('div', { style: 'font-weight: 600; font-size: 16px; color: var(--text); margin-bottom: 6px;' }, 'Здесь будут ваши звонки'),
        el('div', {}, 'Совершайте аудио и видеозвонки в высоком качестве прямо из личных чатов.')
      ));
      return;
    }

    calls.forEach(call => {
      const isOutgoing = Boolean(call.is_outgoing);
      const isVideo = Boolean(call.is_video);
      const durationStr = formatCallDuration(call.duration);
      const timeStr = formatCallDateTime(call.started_at * 1000);

      let statusTitle = '';
      let isMissed = false;

      switch (call.status) {
        case 'completed':
          statusTitle = isOutgoing ? 'Исходящий' : 'Входящий';
          if (durationStr) statusTitle += ` (${durationStr})`;
          break;
        case 'missed':
          statusTitle = isOutgoing ? 'Не отвечен' : 'Пропущенный';
          isMissed = !isOutgoing;
          break;
        case 'declined':
          statusTitle = isOutgoing ? 'Отклонен' : 'Отклоненный';
          isMissed = isOutgoing;
          break;
        case 'cancelled':
          statusTitle = isOutgoing ? 'Отмененный' : 'Пропущенный';
          isMissed = !isOutgoing;
          break;
        case 'busy':
          statusTitle = isOutgoing ? 'Занято' : 'Пропущенный (занято)';
          break;
        default:
          statusTitle = isOutgoing ? 'Исходящий' : 'Входящий';
      }

      const peerContact = {
        user_id: call.peer_id,
        name: call.peer_name || call.peer_nickname || 'Пользователь',
        nickname: call.peer_nickname || ''
      };

      const avatarEl = avatar(peerContact, 44);

      const statusIconSvg = isVideo
        ? `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 7l-7 5 7 5V7z"></path><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>`
        : `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>`;

      const subRow = el('div', {
        style: `display: flex; align-items: center; gap: 6px; font-size: 13px; color: ${isMissed ? 'var(--danger, #ff4d4f)' : 'var(--text-muted)'}; margin-top: 2px;`
      });
      subRow.innerHTML = `<span style="display:inline-flex;align-items:center;line-height:1;">${statusIconSvg}</span> <span>${statusTitle} · ${timeStr}</span>`;

      const infoEl = el('div', { class: 'chatlist-item-info' },
        el('div', { class: 'chatlist-item-name', style: isMissed ? 'color: var(--danger, #ff4d4f);' : '' }, peerContact.name),
        subRow
      );

      const audioBtn = el('button', {
        class: 'icon-btn',
        title: 'Позвонить',
        style: 'padding: 8px; color: var(--accent); border-radius: 50%;'
      });
      audioBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"></path></svg>`;
      audioBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        callManager.startCall(call.peer_id, false);
      });

      const videoBtn = el('button', {
        class: 'icon-btn',
        title: 'Видеозвонок',
        style: 'padding: 8px; color: var(--accent); border-radius: 50%;'
      });
      videoBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M23 7l-7 5 7 5V7z"></path><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>`;
      videoBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        callManager.startCall(call.peer_id, true);
      });

      const actionsEl = el('div', { style: 'display: flex; gap: 4px; align-items: center;' }, audioBtn, videoBtn);

      const item = el('div', { class: 'chatlist-item', style: 'user-select: none;' },
        avatarEl,
        infoEl,
        actionsEl
      );

      item.addEventListener('click', () => {
        navigate(`#chat/${call.peer_id}`);
      });

      listEl.appendChild(item);
    });
  }

  renderItems();

  const ws = getWS();
  const unsub = ws?.on(OP.CALL_LOG, (logEvent) => {
    if (!logEvent) return;
    listCalls(50, 0).then(fresh => {
      calls = fresh || [];
      renderItems();
    }).catch(() => {});
  });

  const obs = new MutationObserver(() => {
    if (!container.isConnected) {
      if (unsub) unsub();
      obs.disconnect();
    }
  });
  obs.observe(document.body, { childList: true, subtree: true });
}
