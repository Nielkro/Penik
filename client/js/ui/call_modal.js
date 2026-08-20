import { callManager } from '../call.js';

let callModalEl = null;

const SVG_ICONS = {
  phoneAccept: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/></svg>`,
  phoneEnd: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91"/><line x1="1" y1="1" x2="23" y2="23"/></svg>`,
  micOn: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="22"/></svg>`,
  micOff: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="1" y1="1" x2="23" y2="23"/><path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V5a3 3 0 0 0-5.94-.6"/><path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23"/><line x1="12" y1="19" x2="12" y2="22"/></svg>`,
  camOn: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>`,
  camOff: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 16v1a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2m3 0h6a2 2 0 0 1 2 2v3.5"/><polygon points="23 7 16 12 23 17 23 7"/><line x1="1" y1="1" x2="23" y2="23"/></svg>`,
};

export function initCallUI() {
  callModalEl = document.createElement('div');
  callModalEl.id = 'call-modal-overlay';
  callModalEl.className = 'call-modal-overlay hidden';
  document.body.appendChild(callModalEl);

  callManager.onCallStateChange = (callState, mediaState) => {
    renderCallModal(callState, mediaState);
  };

  callManager.onMediaStateChange = (mediaState) => {
    updateMediaControlsUI(mediaState);
  };
}

function renderCallModal(callState, mediaState) {
  if (!callState) {
    callModalEl.classList.add('hidden');
    callModalEl.innerHTML = '';
    return;
  }

  callModalEl.classList.remove('hidden');

  if (callState.state === 'INCOMING') {
    callModalEl.innerHTML = `
      <div class="call-card incoming-card">
        <div class="call-avatar-placeholder">
          ${SVG_ICONS.phoneAccept}
        </div>
        <div class="call-info">
          <div class="call-title">Входящий звонок</div>
          <div class="call-subtitle">${callState.isVideo ? 'Видеозвонок' : 'Аудиозвонок'}</div>
        </div>
        <div class="call-actions">
          <button class="call-btn btn-accept" id="btn-call-accept" title="Принять">
            ${SVG_ICONS.phoneAccept}
          </button>
          <button class="call-btn btn-reject" id="btn-call-reject" title="Отклонить">
            ${SVG_ICONS.phoneEnd}
          </button>
        </div>
      </div>
    `;

    document.getElementById('btn-call-accept').addEventListener('click', () => callManager.acceptCall());
    document.getElementById('btn-call-reject').addEventListener('click', () => callManager.rejectCall());
    return;
  }

  if (callState.state === 'DIALING' || callState.state === 'CONNECTING') {
    callModalEl.innerHTML = `
      <div class="call-card dialing-card">
        <div class="call-avatar-placeholder pulsing">
          ${SVG_ICONS.phoneAccept}
        </div>
        <div class="call-info">
          <div class="call-title">${callState.state === 'DIALING' ? 'Вызов...' : 'Подключение...'}</div>
        </div>
        <div class="call-actions">
          <button class="call-btn btn-reject" id="btn-call-cancel" title="Отмена">
            ${SVG_ICONS.phoneEnd}
          </button>
        </div>
      </div>
    `;

    document.getElementById('btn-call-cancel').addEventListener('click', () => callManager.endCall());
    return;
  }

  if (callState.state === 'ACTIVE') {
    callModalEl.innerHTML = `
      <div class="call-active-window">
        <div id="remote-video-container" class="remote-video-container"></div>
        <div id="local-video-container" class="local-video-container"></div>
        <div class="call-controls-bar">
          <button class="call-control-btn ${mediaState.isMuted ? 'active-off' : ''}" id="btn-toggle-mic" title="Микрофон">
            ${mediaState.isMuted ? SVG_ICONS.micOff : SVG_ICONS.micOn}
          </button>
          <button class="call-control-btn ${mediaState.isVideoOff ? 'active-off' : ''}" id="btn-toggle-cam" title="Камера">
            ${mediaState.isVideoOff ? SVG_ICONS.camOff : SVG_ICONS.camOn}
          </button>
          <button class="call-control-btn btn-end-call" id="btn-end-active-call" title="Завершить">
            ${SVG_ICONS.phoneEnd}
          </button>
        </div>
      </div>
    `;

    document.getElementById('btn-toggle-mic').addEventListener('click', () => callManager.toggleMic());
    document.getElementById('btn-toggle-cam').addEventListener('click', () => callManager.toggleCamera());
    document.getElementById('btn-end-active-call').addEventListener('click', () => callManager.endCall());
  }
}

function updateMediaControlsUI(mediaState) {
  const micBtn = document.getElementById('btn-toggle-mic');
  const camBtn = document.getElementById('btn-toggle-cam');

  if (micBtn) {
    micBtn.className = `call-control-btn ${mediaState.isMuted ? 'active-off' : ''}`;
    micBtn.innerHTML = mediaState.isMuted ? SVG_ICONS.micOff : SVG_ICONS.micOn;
  }

  if (camBtn) {
    camBtn.className = `call-control-btn ${mediaState.isVideoOff ? 'active-off' : ''}`;
    camBtn.innerHTML = mediaState.isVideoOff ? SVG_ICONS.camOff : SVG_ICONS.camOn;
  }
}
