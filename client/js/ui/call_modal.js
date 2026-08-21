import { callManager } from '../call.js';
import { avatar } from './components.js';

let callModalEl = null;
let settingsModalEl = null;

const SVG_ICONS = {
  phoneAccept: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/></svg>`,
  phoneEnd: `<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71-.29L.29 13.08a.996.996 0 0 1 0-1.41C2.97 8.98 7.24 7.2 12 7.2s9.03 1.78 11.71 4.47c.39.39.39 1.02 0 1.41l-2.48 2.48c-.18.18-.43.29-.71.29-.27 0-.52-.11-.7-.28-.79-.74-1.69-1.36-2.67-1.85-.33-.16-.56-.5-.56-.9v-3.1C15.15 9.25 13.6 9 12 9z"/></svg>`,
  micOn: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="22"/></svg>`,
  micOff: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="1" y1="1" x2="23" y2="23"/><path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V5a3 3 0 0 0-5.94-.6"/><path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23"/><line x1="12" y1="19" x2="12" y2="22"/></svg>`,
  camOn: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="12" x="2" y="6" rx="2"/><path d="m22 8-6 4 6 4V8Z"/></svg>`,
  camOff: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="12" x="2" y="6" rx="2"/><path d="m22 8-6 4 6 4V8Z"/><line x1="2" x2="22" y1="2" y2="22"/></svg>`,
  screenShare: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/></svg>`,
  screenShareStop: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/><line x1="2" x2="22" y1="2" y2="22"/></svg>`,
  settings: `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg>`,
  close: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>`,
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

  callManager.onTimerTick = (timeString) => {
    const timerEl = document.getElementById('call-duration-timer');
    if (timerEl) {
      timerEl.textContent = timeString;
    }
  };

  callManager.onActiveSpeakersChange = (activeSpeakers) => {
    updateSpeakingIndicators(activeSpeakers);
  };
}

function updateSpeakingIndicators(activeSpeakers) {
  // Update video tiles
  const allTiles = document.querySelectorAll('.video-tile');
  allTiles.forEach(tile => {
    const participantId = tile.getAttribute('data-participant-id');
    if (participantId && activeSpeakers.has(participantId)) {
      tile.classList.add('speaking');
    } else {
      tile.classList.remove('speaking');
    }
  });

  // Update avatar placeholder in active call
  const avatarSlot = document.getElementById('active-peer-avatar-slot');
  if (avatarSlot) {
    let peerSpeaking = false;
    for (const sp of activeSpeakers) {
      if (sp !== callManager.room?.localParticipant?.identity) {
        peerSpeaking = true;
        break;
      }
    }
    if (peerSpeaking) {
      avatarSlot.classList.add('speaking');
    } else {
      avatarSlot.classList.remove('speaking');
    }
  }
}

function renderCallModal(callState, mediaState) {
  if (!callState) {
    callModalEl.classList.add('hidden');
    callModalEl.innerHTML = '';
    closeSettingsModal();
    return;
  }

  callModalEl.classList.remove('hidden');

  const peerContact = callState.peerContact || {
    name: `Пользователь #${callState.fromUserId || callState.toUserId || ''}`,
    nickname: '',
  };
  const peerDisplayName = peerContact.name || (peerContact.nickname ? `@${peerContact.nickname}` : 'Пользователь');

  if (callState.state === 'INCOMING') {
    callModalEl.innerHTML = `
      <div class="call-card incoming-card">
        <div class="call-avatar-wrapper" id="incoming-avatar-slot"></div>
        <div class="call-info">
          <div class="call-title">${peerDisplayName}</div>
          <div class="call-subtitle">Входящий ${callState.isVideo ? 'видеозвонок' : 'аудиозвонок'}</div>
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

    const slot = document.getElementById('incoming-avatar-slot');
    if (slot) slot.appendChild(avatar(peerContact, 88));

    document.getElementById('btn-call-accept').addEventListener('click', () => callManager.acceptCall());
    document.getElementById('btn-call-reject').addEventListener('click', () => callManager.rejectCall());
    return;
  }

  if (callState.state === 'DIALING' || callState.state === 'CONNECTING') {
    callModalEl.innerHTML = `
      <div class="call-card dialing-card">
        <div class="call-avatar-wrapper pulsing" id="dialing-avatar-slot"></div>
        <div class="call-info">
          <div class="call-title">${peerDisplayName}</div>
          <div class="call-subtitle">${callState.state === 'DIALING' ? 'Исходящий вызов...' : 'Подключение...'}</div>
        </div>
        <div class="call-actions">
          <button class="call-btn btn-reject" id="btn-call-cancel" title="Отмена">
            ${SVG_ICONS.phoneEnd}
          </button>
        </div>
      </div>
    `;

    const slot = document.getElementById('dialing-avatar-slot');
    if (slot) slot.appendChild(avatar(peerContact, 88));

    document.getElementById('btn-call-cancel').addEventListener('click', () => callManager.endCall());
    return;
  }

  if (callState.state === 'ACTIVE') {
    const hasLocalVideo = !mediaState.isVideoOff || mediaState.isScreenShareOn;
    const hasRemoteVideo = !!mediaState.hasRemoteVideo;

    callModalEl.innerHTML = `
      <div class="call-active-window ${!hasRemoteVideo ? 'no-remote-video' : ''}">
        <div class="call-header-bar">
          <div class="call-header-info">
            <span class="call-peer-title">${peerDisplayName}</span>
            <span class="call-duration-badge" id="call-duration-timer">00:00</span>
          </div>
        </div>
        <div id="remote-video-container" class="remote-video-container ${!hasRemoteVideo ? 'hidden-stream' : ''}"></div>
        <div id="remote-placeholder-container" class="call-participant-placeholder ${hasRemoteVideo ? 'hidden' : ''}">
          <div class="call-active-avatar-wrap pulsing" id="active-peer-avatar-slot"></div>
          <div class="call-active-peer-name">${peerDisplayName}</div>
          <div class="call-voice-wave-container">
            <span class="wave-bar"></span>
            <span class="wave-bar"></span>
            <span class="wave-bar"></span>
            <span class="wave-bar"></span>
          </div>
        </div>
        <div id="local-video-container" class="local-video-container ${!hasLocalVideo ? 'hidden' : ''}"></div>
        <div class="call-controls-bar">
          <button class="call-control-btn ${mediaState.isMuted ? 'active-off' : ''}" id="btn-toggle-mic" title="Микрофон">
            ${mediaState.isMuted ? SVG_ICONS.micOff : SVG_ICONS.micOn}
          </button>
          <button class="call-control-btn ${mediaState.isVideoOff ? 'active-off' : ''}" id="btn-toggle-cam" title="Камера">
            ${mediaState.isVideoOff ? SVG_ICONS.camOff : SVG_ICONS.camOn}
          </button>
          <button class="call-control-btn ${mediaState.isScreenShareOn ? 'active-on' : ''}" id="btn-toggle-screen" title="Демонстрация экрана">
            ${mediaState.isScreenShareOn ? SVG_ICONS.screenShareStop : SVG_ICONS.screenShare}
          </button>
          <button class="call-control-btn" id="btn-call-settings" title="Настройки устройств">
            ${SVG_ICONS.settings}
          </button>
          <button class="call-control-btn btn-end-call" id="btn-end-active-call" title="Завершить звонок">
            ${SVG_ICONS.phoneEnd}
          </button>
        </div>
      </div>
    `;

    const activeSlot = document.getElementById('active-peer-avatar-slot');
    if (activeSlot) activeSlot.appendChild(avatar(peerContact, 100));

    document.getElementById('btn-toggle-mic').addEventListener('click', () => callManager.toggleMic());
    document.getElementById('btn-toggle-cam').addEventListener('click', () => callManager.toggleCamera());
    document.getElementById('btn-toggle-screen').addEventListener('click', () => callManager.toggleScreenShare());
    document.getElementById('btn-call-settings').addEventListener('click', () => openSettingsModal());
    document.getElementById('btn-end-active-call').addEventListener('click', () => callManager.endCall());
  }
}

async function openSettingsModal() {
  if (settingsModalEl) {
    settingsModalEl.remove();
  }

  const devices = await callManager.getDevices();

  settingsModalEl = document.createElement('div');
  settingsModalEl.className = 'call-settings-dialog-overlay';
  settingsModalEl.innerHTML = `
    <div class="call-settings-card">
      <div class="call-settings-header">
        <h3>Настройки устройств</h3>
        <button class="btn-close-settings" id="btn-close-device-settings">
          ${SVG_ICONS.close}
        </button>
      </div>
      <div class="call-settings-body">
        <div class="settings-group">
          <label>Микрофон</label>
          <select id="select-audio-input" class="call-device-select">
            ${devices.audioInputs.map(d => `<option value="${d.deviceId}" ${d.deviceId === callManager.selectedAudioInputId ? 'selected' : ''}>${d.label || `Микрофон (${d.deviceId.slice(0, 5)}...)`}</option>`).join('')}
          </select>
        </div>
        <div class="settings-group">
          <label>Камера</label>
          <select id="select-video-input" class="call-device-select">
            ${devices.videoInputs.map(d => `<option value="${d.deviceId}" ${d.deviceId === callManager.selectedVideoInputId ? 'selected' : ''}>${d.label || `Камера (${d.deviceId.slice(0, 5)}...)`}</option>`).join('')}
          </select>
        </div>
        <div class="settings-group">
          <label>Динамики / Наушники</label>
          <select id="select-audio-output" class="call-device-select">
            ${devices.audioOutputs.map(d => `<option value="${d.deviceId}" ${d.deviceId === callManager.selectedAudioOutputId ? 'selected' : ''}>${d.label || `Динамики (${d.deviceId.slice(0, 5)}...)`}</option>`).join('')}
          </select>
        </div>
      </div>
    </div>
  `;

  document.body.appendChild(settingsModalEl);

  document.getElementById('btn-close-device-settings').addEventListener('click', closeSettingsModal);
  settingsModalEl.addEventListener('click', (e) => {
    if (e.target === settingsModalEl) closeSettingsModal();
  });

  const micSelect = /** @type {HTMLSelectElement|null} */ (document.getElementById('select-audio-input'));
  if (micSelect) {
    micSelect.addEventListener('change', () => {
      callManager.setAudioInputDevice(micSelect.value);
    });
  }

  const camSelect = /** @type {HTMLSelectElement|null} */ (document.getElementById('select-video-input'));
  if (camSelect) {
    camSelect.addEventListener('change', () => {
      callManager.setVideoInputDevice(camSelect.value);
    });
  }

  const outSelect = /** @type {HTMLSelectElement|null} */ (document.getElementById('select-audio-output'));
  if (outSelect) {
    outSelect.addEventListener('change', () => {
      callManager.setAudioOutputDevice(outSelect.value);
    });
  }
}

function closeSettingsModal() {
  if (settingsModalEl) {
    settingsModalEl.remove();
    settingsModalEl = null;
  }
}

function updateMediaControlsUI(mediaState) {
  const micBtn = document.getElementById('btn-toggle-mic');
  const camBtn = document.getElementById('btn-toggle-cam');
  const screenBtn = document.getElementById('btn-toggle-screen');
  const localContainer = document.getElementById('local-video-container');
  const remoteContainer = document.getElementById('remote-video-container');
  const remotePlaceholder = document.getElementById('remote-placeholder-container');
  const activeWin = callModalEl ? callModalEl.querySelector('.call-active-window') : null;

  if (micBtn) {
    micBtn.className = `call-control-btn ${mediaState.isMuted ? 'active-off' : ''}`;
    micBtn.innerHTML = mediaState.isMuted ? SVG_ICONS.micOff : SVG_ICONS.micOn;
  }

  if (camBtn) {
    camBtn.className = `call-control-btn ${mediaState.isVideoOff ? 'active-off' : ''}`;
    camBtn.innerHTML = mediaState.isVideoOff ? SVG_ICONS.camOff : SVG_ICONS.camOn;
  }

  if (screenBtn) {
    screenBtn.className = `call-control-btn ${mediaState.isScreenShareOn ? 'active-on' : ''}`;
    screenBtn.innerHTML = mediaState.isScreenShareOn ? SVG_ICONS.screenShareStop : SVG_ICONS.screenShare;
  }

  const hasLocalVideo = !mediaState.isVideoOff || mediaState.isScreenShareOn;
  if (localContainer) {
    if (hasLocalVideo) {
      localContainer.classList.remove('hidden');
    } else {
      localContainer.classList.add('hidden');
      localContainer.innerHTML = '';
    }
  }

  const hasRemoteVideo = !!mediaState.hasRemoteVideo;
  if (remoteContainer && remotePlaceholder && activeWin) {
    if (hasRemoteVideo) {
      remoteContainer.classList.remove('hidden-stream');
      remotePlaceholder.classList.add('hidden');
      activeWin.classList.remove('no-remote-video');
    } else {
      remoteContainer.classList.add('hidden-stream');
      remotePlaceholder.classList.remove('hidden');
      activeWin.classList.add('no-remote-video');
      activeWin.classList.remove('swapped-layout');
    }
  }
}


