import { ws, OP } from './ws.js';
import { showToast } from './ui/components.js';
import { getContact, saveContact } from './storage.js';
import { getUserById } from './api.js';
import { callSounds } from './sounds.js';

let _livekitModule = null;
async function getLiveKit() {
  if (!_livekitModule) {
    _livekitModule = await import('livekit-client');
  }
  return _livekitModule;
}

export class CallManager {
  constructor() {
    this.currentCall = null;
    this.room = null;
    this.isMuted = false;
    this.isVideoOff = false;
    this.isScreenShareOn = false;
    this.hasRemoteVideo = false;
    // Set while LiveKit is re-establishing the session, so the UI can say so
    // instead of showing a frozen picture.
    this.isReconnecting = false;
    // False while the peer's signaling socket is inside the server-side grace
    // window (typically a network switch on their end).
    this.peerOnline = true;

    this.callStartTime = null;
    this.timerInterval = null;
    this.activeSpeakers = new Set(); // Set of participant identities

    this.selectedAudioInputId = null;
    this.selectedAudioOutputId = null;
    this.selectedVideoInputId = null;

    this.onCallStateChange = null;
    this.onMediaStateChange = null;
    this.onActiveSpeakersChange = null;
    this.onTimerTick = null;

    // Teardown callbacks for window-level listeners registered per video tile.
    // Tiles are recreated on every track publish, so without this the listeners
    // accumulate for the lifetime of the page.
    this._tileListenerCleanups = [];
  }

  _releaseTileListeners() {
    for (const release of this._tileListenerCleanups) {
      try {
        release();
      } catch (e) {
        console.warn('Failed to release tile listener:', e);
      }
    }
    this._tileListenerCleanups = [];
  }

  init() {
    ws.on(OP.CALL_INCOMING, (payload) => this._handleIncomingCall(payload));
    ws.on(OP.CALL_ACCEPTED, (payload) => this._handleCallAccepted(payload));
    ws.on(OP.CALL_REJECT, (payload) => this._handleCallReject(payload));
    ws.on(OP.CALL_END, (payload) => this._handleCallEnd(payload));
    ws.on(OP.CALL_TAKEN, (payload) => this._handleCallTaken(payload));
    ws.on(OP.CALL_STATE, (payload) => this._handleCallState(payload));
    ws.on(OP.CALL_PEER_STATE, (payload) => this._handleCallPeerState(payload));
  }

  /**
   * True when a server frame refers to the call we are currently in. The server
   * rings every device of the callee, so a frame about a call this device is not
   * (or no longer) part of must not tear down what is on screen.
   * @param {{call_id?: string}} payload
   */
  _isCurrentCall(payload) {
    if (!this.currentCall) return false;
    const incomingId = payload && payload.call_id;
    if (!incomingId || !this.currentCall.callId) return true;
    return incomingId === this.currentCall.callId;
  }

  async _resolveContact(userId) {
    if (!userId) return null;
    let contact = await getContact(Number(userId));
    if (!contact || contact.name === 'Неизвестный') {
      try {
        const res = await getUserById(String(userId));
        contact = res.user || res;
        if (contact) {
          contact.user_id = Number(userId);
          await saveContact(contact);
        }
      } catch (e) {
        console.warn('Failed to resolve contact for call:', e);
      }
    }
    return contact || { user_id: Number(userId), name: `Пользователь #${userId}`, nickname: '' };
  }

  async startCall(toUserId, isVideo = false) {
    if (this.currentCall) {
      showToast('Вы уже находитесь в звонке', 'error');
      return;
    }

    const peerContact = await this._resolveContact(toUserId);

    this.currentCall = {
      state: 'DIALING',
      toUserId,
      isVideo,
      callId: null,
      peerContact,
    };

    callSounds.playDialing();

    ws.send(OP.CALL_OFFER, {
      to_user_id: toUserId,
      is_video: isVideo,
    });

    this._dialTimeout = setTimeout(() => {
      if (this.currentCall && this.currentCall.state === 'DIALING') {
        showToast('Нет ответа', 'info');
        this.rejectCall('declined');
      }
    }, 90_000);

    this._notifyState();
  }

  acceptCall() {
    if (!this.currentCall || this.currentCall.state !== 'INCOMING') return;

    callSounds.stopAll();
    const { callId, token, livekitUrl, livekitFallbackUrl } = this.currentCall;
    this.currentCall.state = 'CONNECTING';
    this._notifyState();

    ws.send(OP.CALL_ACCEPT, { call_id: callId });

    this._connectLiveKit(livekitUrl, livekitFallbackUrl, token);
  }

  rejectCall(reason = 'declined') {
    if (!this.currentCall) return;

    callSounds.stopAll();
    ws.send(OP.CALL_REJECT, {
      call_id: this.currentCall.callId || '',
      to_user_id: this.currentCall.toUserId || this.currentCall.fromUserId,
      reason,
    });

    this.cleanup();
  }

  endCall() {
    if (!this.currentCall) return;

    callSounds.playEnded();
    ws.send(OP.CALL_END, {
      call_id: this.currentCall.callId || '',
      to_user_id: this.currentCall.toUserId || this.currentCall.fromUserId,
    });

    this.cleanup();
  }

  async toggleMic() {
    if (!this.room) return;
    this.isMuted = !this.isMuted;
    await this.room.localParticipant.setMicrophoneEnabled(!this.isMuted);
    this._notifyMediaState();
  }

  async toggleCamera() {
    if (!this.room) return;
    const next = !this.isVideoOff;
    try {
      await this.room.localParticipant.setCameraEnabled(!next);
    } catch (e) {
      console.warn('Failed to toggle camera:', e);
      showToast('Не удалось переключить камеру', 'error');
      // Fall through to the resync: the flag must reflect the real publication
      // state, not the state we hoped for.
    }
    if (next) {
      const container = document.getElementById('local-video-container');
      if (container && !this.isScreenShareOn) {
        container.innerHTML = '';
      }
    }
    this._syncLocalVideoState();
  }

  async toggleScreenShare() {
    if (!this.room) return;
    try {
      const nextState = !this.isScreenShareOn;
      await this.room.localParticipant.setScreenShareEnabled(nextState, {
        audio: true,
        selfBrowserSurface: 'include',
        surfaceSwitching: 'include',
      });
      this.isScreenShareOn = nextState;
      if (!this.isScreenShareOn && this.isVideoOff) {
        const container = document.getElementById('local-video-container');
        if (container) container.innerHTML = '';
      }
      this._notifyMediaState();
    } catch (e) {
      console.warn('Screen share toggled/cancelled:', e);
      this.isScreenShareOn = false;
      if (this.isVideoOff) {
        const container = document.getElementById('local-video-container');
        if (container) container.innerHTML = '';
      }
      this._notifyMediaState();
    }
  }

  async getDevices() {
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      return {
        audioInputs: devices.filter(d => d.kind === 'audioinput'),
        audioOutputs: devices.filter(d => d.kind === 'audiooutput'),
        videoInputs: devices.filter(d => d.kind === 'videoinput'),
      };
    } catch (e) {
      console.warn('Failed to enumerate media devices:', e);
      return { audioInputs: [], audioOutputs: [], videoInputs: [] };
    }
  }

  async setAudioInputDevice(deviceId) {
    this.selectedAudioInputId = deviceId;
    if (this.room) {
      await this.room.switchActiveDevice('audioinput', deviceId);
    }
  }

  async setAudioOutputDevice(deviceId) {
    this.selectedAudioOutputId = deviceId;
    if (this.room) {
      await this.room.switchActiveDevice('audiooutput', deviceId);
    }
  }

  async setVideoInputDevice(deviceId) {
    this.selectedVideoInputId = deviceId;
    if (this.room) {
      await this.room.switchActiveDevice('videoinput', deviceId);
    }
  }

  _startTimer() {
    this._stopTimer();
    this.callStartTime = Date.now();
    this._runTimer();
  }

  /**
   * Continues the call timer from a server-provided answer time, so a reconnect
   * mid-call does not restart the duration from 00:00.
   * @param {number} startedAtMs
   */
  _resumeTimer(startedAtMs) {
    if (!startedAtMs) return;
    this._stopTimer();
    this.callStartTime = startedAtMs;
    this._runTimer();
  }

  _runTimer() {
    this.timerInterval = setInterval(() => {
      if (this.callStartTime && typeof this.onTimerTick === 'function') {
        const elapsedSec = Math.floor((Date.now() - this.callStartTime) / 1000);
        const mins = String(Math.floor(elapsedSec / 60)).padStart(2, '0');
        const secs = String(elapsedSec % 60).padStart(2, '0');
        this.onTimerTick(`${mins}:${secs}`);
      }
    }, 1000);
  }

  _stopTimer() {
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
      this.timerInterval = null;
    }
    this.callStartTime = null;
  }

  cleanup() {
    callSounds.stopAll();
    this._stopTimer();
    this._releaseTileListeners();
    clearTimeout(this._dialTimeout);
    if (this.room) {
      try {
        this.room.removeAllListeners();
        this.room.disconnect();
      } catch (e) {
        console.error(e);
      }
      this.room = null;
    }

    this.currentCall = null;
    this.isMuted = false;
    this.isVideoOff = false;
    this.isScreenShareOn = false;
    this.hasRemoteVideo = false;
    this.isReconnecting = false;
    this.peerOnline = true;
    this.activeSpeakers.clear();
    this._notifyState();
  }

  async _handleIncomingCall(payload) {
    if (this.currentCall) {
      ws.send(OP.CALL_REJECT, {
        call_id: payload.call_id,
        to_user_id: payload.from_user_id,
        reason: 'busy',
      });
      return;
    }

    const peerContact = await this._resolveContact(payload.from_user_id);

    this.currentCall = {
      state: 'INCOMING',
      callId: payload.call_id,
      fromUserId: payload.from_user_id,
      isVideo: payload.is_video,
      roomName: payload.room_name,
      livekitUrl: payload.livekit_url,
      livekitFallbackUrl: payload.livekit_fallback_url,
      token: payload.token,
      peerContact,
    };

    callSounds.playRingtone();
    this._notifyState();
  }

  async _handleCallAccepted(payload) {
    if (!this.currentCall || this.currentCall.state !== 'DIALING') return;

    callSounds.stopAll();
    clearTimeout(this._dialTimeout);
    this.currentCall.callId = payload.call_id;
    this.currentCall.state = 'CONNECTING';
    if (!this.currentCall.peerContact && payload.to_user_id) {
      this.currentCall.peerContact = await this._resolveContact(payload.to_user_id);
    }
    this._notifyState();

    this._connectLiveKit(payload.livekit_url, payload.livekit_fallback_url, payload.token);
  }

  _handleCallReject(payload) {
    if (!this._isCurrentCall(payload)) return;

    callSounds.playBusy();
    let reason;
    if (payload.reason === 'busy') reason = 'Пользователь занят';
    else if (payload.reason === 'offline') reason = 'Пользователь не в сети';
    else reason = 'Звонок отклонен';
    showToast(reason, 'info');
    this.cleanup();
  }

  _handleCallEnd(payload) {
    if (!this._isCurrentCall(payload)) return;
    callSounds.playEnded();
    showToast('Звонок завершен', 'info');
    this.cleanup();
  }

  /**
   * Another device of this account answered or declined the same incoming call,
   * so this device just stops ringing without ending the call for the one that
   * picked up.
   * @param {{call_id?: string, reason?: string}} payload
   */
  _handleCallTaken(payload) {
    if (!this._isCurrentCall(payload)) return;
    callSounds.stopAll();
    // Only a ringing/dialing device can be superseded; a device that is already
    // in the LiveKit room is the one that answered.
    if (this.currentCall.state !== 'INCOMING' && this.currentCall.state !== 'DIALING') return;
    showToast(
      payload && payload.reason === 'declined'
        ? 'Звонок отклонен на другом устройстве'
        : 'Звонок принят на другом устройстве',
      'info'
    );
    // Local teardown only: the server already knows this device is out, and
    // sending a reject here would hang up on the device that answered.
    this.cleanup();
  }

  /**
   * Sent right after the signaling socket reconnects while this device still
   * owns a side of a live call. The server held the call open through the
   * network switch, so all this does is re-sync the call id and timer — the
   * LiveKit room reconnects on its own.
   * @param {{call_id?: string, peer_user_id?: number, is_video?: boolean, accepted?: boolean, answered_at?: number}} payload
   */
  async _handleCallState(payload) {
    if (!payload || !payload.call_id) return;
    if (this.currentCall) {
      // Adopt the id if this device was dialing and lost the accept frame.
      this.currentCall.callId = payload.call_id;
      if (payload.answered_at) {
        this._resumeTimer(payload.answered_at * 1000);
      }
      return;
    }
    if (!payload.accepted) return;
    // The socket came back but the tab lost its call state entirely (reload).
    // Without a LiveKit token there is nothing to rejoin, so release the call
    // server-side rather than leaving the peer talking to nobody.
    ws.send(OP.CALL_END, {
      call_id: payload.call_id,
      to_user_id: payload.peer_user_id || 0,
    });
  }

  /**
   * The peer's signaling link dropped (or came back) while the call is held open
   * by the server grace period.
   * @param {{call_id?: string, online?: boolean}} payload
   */
  _handleCallPeerState(payload) {
    if (!this._isCurrentCall(payload)) return;
    const online = !!(payload && payload.online);
    if (this.peerOnline === online) return;
    this.peerOnline = online;
    showToast(online ? 'Собеседник снова в сети' : 'Собеседник теряет связь…', 'info');
    this._notifyMediaState();
  }

  async _connectLiveKit(primaryUrl, fallbackUrl, token) {
    if (!token && typeof fallbackUrl === 'string' && !fallbackUrl.startsWith('ws://') && !fallbackUrl.startsWith('wss://')) {
      token = fallbackUrl;
      fallbackUrl = null;
    }

    const { Room, RoomEvent, VideoPresets } = await getLiveKit();

    const urlsToTry = [primaryUrl];
    if (fallbackUrl && fallbackUrl !== primaryUrl) {
      urlsToTry.push(fallbackUrl);
    }

    let lastErr = null;
    for (let attempt = 0; attempt < urlsToTry.length; attempt++) {
      const url = urlsToTry[attempt];
      try {
        this.room = new Room({
          disconnectOnPageLeave: false,
          adaptiveStream: {
            pixelDensity: 2,
          },
          dynacast: true,
          audioCaptureDefaults: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
            deviceId: this.selectedAudioInputId || undefined,
          },
          videoCaptureDefaults: {
            resolution: { width: 1920, height: 1080 },
            frameRate: 60,
            deviceId: this.selectedVideoInputId || undefined,
          },
          publishDefaults: {
            videoCodec: 'vp9',
            backupCodec: { codec: 'vp8' },
            videoEncoding: {
              maxBitrate: 6_000_000,
              maxFramerate: 60,
            },
            videoSimulcastLayers: [
              VideoPresets.h720,
              VideoPresets.h540,
            ],
            audioPreset: {
              maxBitrate: 128_000,
            },
            red: true,
            dtx: false,
            simulcast: true,
            screenShareEncoding: {
              maxBitrate: 8_000_000,
              maxFramerate: 60,
            },
          },
        });

        this.room
          .on(RoomEvent.TrackSubscribed, (track, publication, participant) => {
            this._attachRemoteTrack(track, participant);
          })
          .on(RoomEvent.TrackUnsubscribed, (track, publication, participant) => {
            track.detach();
            const elements = document.querySelectorAll(`[data-track-sid="${track.sid}"]`);
            elements.forEach(el => el.remove());
            this._checkRemoteTracks();
          })
          .on(RoomEvent.TrackMuted, (publication, participant) => {
            if (publication.kind === 'video' && participant !== this.room?.localParticipant) {
              this._checkRemoteTracks();
            }
          })
          .on(RoomEvent.TrackUnmuted, (publication, participant) => {
            if (publication.kind === 'video' && participant !== this.room?.localParticipant) {
              this._checkRemoteTracks();
            }
          })
          .on(RoomEvent.ActiveSpeakersChanged, (speakers) => {
            this.activeSpeakers.clear();
            for (const sp of speakers) {
              this.activeSpeakers.add(sp.identity);
            }
            if (typeof this.onActiveSpeakersChange === 'function') {
              this.onActiveSpeakersChange(this.activeSpeakers);
            }
          })
          .on(RoomEvent.LocalTrackPublished, (publication) => {
            if (publication.track) {
              this._attachLocalTrack(publication.track);
            }
          })
          .on(RoomEvent.LocalTrackUnpublished, (publication) => {
            if (publication.track) {
              publication.track.detach();
            }
            // A full LiveKit reconnect republishes every local track under a new
            // SID. Without removing the old tile the dead one keeps its
            // primary-tile class and _updateTileLayout leaves a black rectangle
            // as the main view while the live track is demoted to PiP.
            this._removeTilesForPublication(publication);
            if (publication.source === 'screen_share') {
              this.isScreenShareOn = false;
              if (this.isVideoOff) {
                const container = document.getElementById('local-video-container');
                if (container) container.innerHTML = '';
              }
            }
            this._notifyMediaState();
          })
          .on(RoomEvent.Reconnecting, () => {
            // Media is re-negotiating; keep the call up and say so.
            this.isReconnecting = true;
            this._notifyMediaState();
          })
          .on(RoomEvent.Reconnected, () => {
            this.isReconnecting = false;
            const wantCamera = !this.isVideoOff;
            // Events for tracks that were (re)published while the socket was
            // down are not replayed, so rebuild the whole video state from the
            // room instead of waiting for notifications that will never come.
            this._resyncTracks();
            this._restoreCameraIfNeeded(wantCamera);
          })
          .on(RoomEvent.Disconnected, (reason) => {
            // Do not treat a background tab freeze/pagehide as a hangup.
            // With disconnectOnPageLeave=false LiveKit no longer disconnects on
            // freeze/pagehide, but a transient network glitch still fires this
            // event - that should not send CALL_END to the peer. The server
            // will end the call via WS CleanupDeviceCalls only after the Penik
            // WS actually drops, and the user can explicitly hang up via endCall().
            if (this.currentCall && this.currentCall.state === 'ACTIVE') {
              console.warn('[call] LiveKit room disconnected:', reason);
              // Keep peer in call; just tear down local media. If the user
              // really closed the tab, the Penik WS close will trigger the
              // server-side CallEnd for the peer.
              this.cleanup();
            }
          });

        await this.room.connect(url, token);

        callSounds.playConnected();

        if (this.currentCall) {
          if (attempt > 0) {
            showToast('Подключено к резервному серверу звонков — качество может быть хуже', 'info');
          }
          this.currentCall.state = 'ACTIVE';
          this.isVideoOff = !this.currentCall.isVideo;
          this._startTimer();
          this._notifyState();
        }

        await this.room.localParticipant.setMicrophoneEnabled(true);
        if (this.currentCall && this.currentCall.isVideo) {
          await this.room.localParticipant.setCameraEnabled(true);
        }
        // Tracks the peer published before this modal existed produced tiles
        // that had nowhere to go, so rebuild once the DOM is in place.
        this._resyncTracks();
        return;
      } catch (err) {
        console.warn(`Failed to connect to LiveKit URL ${url}:`, err);
        lastErr = err;
        if (this.room) {
          try { this.room.removeAllListeners(); } catch (_) {}
          try { this.room.disconnect(); } catch (_) {}
          this.room = null;
        }
      }
    }

    console.error('LiveKit connection error across all endpoints:', lastErr);
    showToast('Ошибка подключения к серверу звонка', 'error');
    // Notify the server so both users leave the busy state.
    if (this.currentCall) {
      ws.send(OP.CALL_REJECT, {
        call_id: this.currentCall.callId || '',
        to_user_id: this.currentCall.toUserId || this.currentCall.fromUserId,
        reason: 'declined',
      });
    }
    this.cleanup();
  }

  _attachRemoteTrack(track, participant) {
    if (track.kind === 'video') {
      const container = document.getElementById('remote-video-container');
      if (container) {
        let tile = /** @type {HTMLElement|null} */ (container.querySelector(`[data-track-sid="${track.sid}"]`));
        if (!tile) {
          tile = document.createElement('div');
          tile.className = 'video-tile remote-tile';
          tile.dataset.trackSid = track.sid;
          tile.dataset.participantId = participant.identity;
          tile.dataset.source = track.source || 'camera';
          this._makeTileDraggable(tile);
          container.appendChild(tile);
        } else {
          tile.innerHTML = '';
        }

        const element = track.attach();
        element.className = 'video-stream-element';
        tile.appendChild(element);

        tile.onclick = (e) => {
          if (tile && tile.dataset.dragged === 'true') return;
          e.stopPropagation();
          if (tile) this._setPrimaryTile(tile);
        };
      }
      this._updateTileLayout();
      this._checkRemoteTracks();
    } else if (track.kind === 'audio') {
      track.attach();
    }
  }

  _attachLocalTrack(track) {
    if (track.kind !== 'video') return;
    const container = document.getElementById('local-video-container');
    if (!container) return;

    let tile = /** @type {HTMLElement|null} */ (container.querySelector(`[data-track-sid="${track.sid}"]`));
    if (!tile) {
      tile = document.createElement('div');
      tile.className = 'video-tile local-tile';
      tile.dataset.trackSid = track.sid;
      tile.dataset.source = track.source || 'camera';
      tile.dataset.participantId = this.room?.localParticipant?.identity || 'local';
      this._makeTileDraggable(tile);
      container.appendChild(tile);
    } else {
      tile.innerHTML = '';
    }

    const element = track.attach();
    element.className = 'video-stream-element';
    element.muted = true;
    if (track.source === 'camera') {
      element.classList.add('camera-mirror');
    }
    tile.appendChild(element);

    tile.onclick = (e) => {
      if (tile && tile.dataset.dragged === 'true') return;
      e.stopPropagation();
      if (tile) this._setPrimaryTile(tile);
    };

    this._updateTileLayout();
    this._notifyMediaState();
  }

  _makeTileDraggable(tile) {
    let isDragging = false;
    let startX = 0;
    let startY = 0;
    let startLeft = 0;
    let startTop = 0;

    const onStart = (clientX, clientY) => {
      if (!tile.classList.contains('pip-tile')) return;
      isDragging = true;
      tile.dataset.dragged = 'false';
      startX = clientX;
      startY = clientY;
      const rect = tile.getBoundingClientRect();
      startLeft = rect.left;
      startTop = rect.top;

      // Unset right/bottom and convert to fixed pixel coordinates
      tile.style.right = 'auto';
      tile.style.bottom = 'auto';
      tile.style.left = `${startLeft}px`;
      tile.style.top = `${startTop}px`;
    };

    const onMove = (clientX, clientY) => {
      if (!isDragging) return;
      const dx = clientX - startX;
      const dy = clientY - startY;
      if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
        tile.dataset.dragged = 'true';
      }
      let newLeft = startLeft + dx;
      let newTop = startTop + dy;

      // Constrain inside call window bounds
      const win = document.querySelector('.call-active-window');
      if (win) {
        const winRect = win.getBoundingClientRect();
        const tileW = tile.offsetWidth;
        const tileH = tile.offsetHeight;
        newLeft = Math.max(winRect.left + 8, Math.min(winRect.right - tileW - 8, newLeft));
        newTop = Math.max(winRect.top + 8, Math.min(winRect.bottom - tileH - 80, newTop));
      }

      tile.style.left = `${newLeft}px`;
      tile.style.top = `${newTop}px`;
    };

    const onEnd = () => {
      if (!isDragging) return;
      isDragging = false;
      setTimeout(() => {
        tile.dataset.dragged = 'false';
      }, 50);
    };

    const onMouseMove = (/** @type {MouseEvent} */ e) => onMove(e.clientX, e.clientY);
    const onTouchMove = (/** @type {TouchEvent} */ e) => {
      if (e.touches.length === 1) onMove(e.touches[0].clientX, e.touches[0].clientY);
    };

    tile.addEventListener('mousedown', (e) => onStart(e.clientX, e.clientY));
    tile.addEventListener('touchstart', (e) => {
      if (e.touches.length === 1) onStart(e.touches[0].clientX, e.touches[0].clientY);
    }, { passive: true });

    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onEnd);
    window.addEventListener('touchmove', onTouchMove, { passive: true });
    window.addEventListener('touchend', onEnd);

    // Registered on window so a drag keeps tracking outside the tile bounds;
    // released when the call ends.
    this._tileListenerCleanups.push(() => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onEnd);
      window.removeEventListener('touchmove', onTouchMove);
      window.removeEventListener('touchend', onEnd);
    });
  }

  _setPrimaryTile(selectedTile) {
    const activeWin = document.querySelector('.call-active-window');
    if (!activeWin) return;

    const allTiles = /** @type {HTMLElement[]} */ (Array.from(document.querySelectorAll('.video-tile')));
    if (allTiles.length <= 1) return;

    const isCurrentlyPrimary = selectedTile.classList.contains('primary-tile');

    if (isCurrentlyPrimary) {
      const otherTile = allTiles.find(t => t !== selectedTile);
      if (otherTile) {
        this._setPrimaryTile(otherTile);
      }
      return;
    }

    allTiles.forEach(t => {
      t.classList.remove('primary-tile');
      t.classList.add('pip-tile');
    });

    selectedTile.classList.remove('pip-tile');
    selectedTile.classList.add('primary-tile');
    selectedTile.style.left = '';
    selectedTile.style.top = '';
    selectedTile.style.right = '';
    selectedTile.style.bottom = '';
  }

  _updateTileLayout() {
    const allTiles = /** @type {HTMLElement[]} */ (Array.from(document.querySelectorAll('.video-tile')));
    if (allTiles.length === 0) return;

    const hasPrimary = allTiles.some(t => t.classList.contains('primary-tile'));

    if (!hasPrimary) {
      let primary = allTiles.find(t => t.dataset.source === 'screen_share' && t.classList.contains('remote-tile'))
        || allTiles.find(t => t.dataset.source === 'screen_share')
        || allTiles.find(t => t.classList.contains('remote-tile'))
        || allTiles[0];

      allTiles.forEach(t => {
        if (t === primary) {
          t.classList.add('primary-tile');
          t.classList.remove('pip-tile');
          t.style.left = '';
          t.style.top = '';
          t.style.right = '';
          t.style.bottom = '';
        } else {
          t.classList.add('pip-tile');
          t.classList.remove('primary-tile');
        }
      });
    } else {
      allTiles.forEach(t => {
        if (!t.classList.contains('primary-tile')) {
          t.classList.add('pip-tile');
        }
      });
    }
  }

  _checkRemoteTracks() {
    let hasVideo = false;
    if (this.room) {
      for (const participant of this.room.remoteParticipants.values()) {
        for (const pub of participant.videoTrackPublications.values()) {
          if (pub.isSubscribed && pub.track && !pub.isMuted) {
            hasVideo = true;
            break;
          }
        }
      }
    }
    this.hasRemoteVideo = hasVideo;
    this._updateTileLayout();
    this._notifyMediaState();
  }

  /**
   * Removes every tile belonging to a publication. Tiles are keyed by track SID,
   * which changes on republish, so the SID is taken from the publication itself
   * (its track may already be detached by the time this runs).
   * @param {{trackSid?: string, track?: {sid?: string}}} publication
   */
  _removeTilesForPublication(publication) {
    const sid = (publication && (publication.trackSid || (publication.track && publication.track.sid))) || null;
    if (!sid) return;
    document.querySelectorAll(`[data-track-sid="${sid}"]`).forEach((el) => el.remove());
  }

  /**
   * Rebuilds the whole video surface from the current room state.
   *
   * A LiveKit full reconnect unpublishes and republishes every local track with
   * a new SID and re-subscribes the remote ones, but the per-track events that
   * normally build the tiles are not replayed for what already existed. Without
   * this the local preview and the peer's picture stay black until the user
   * toggles the camera by hand.
   *
   * Only video is rebuilt: audio elements live outside these containers and are
   * re-attached by TrackSubscribed, so touching them here would just duplicate
   * the peer's audio.
   */
  _resyncTracks() {
    if (!this.room) return;

    for (const id of ['local-video-container', 'remote-video-container']) {
      const container = document.getElementById(id);
      if (!container) continue;
      // Detach through the SDK rather than wiping innerHTML, otherwise the track
      // keeps a reference to an orphaned element and goes on rendering into it.
      container.querySelectorAll('video').forEach((el) => el.remove());
      container.innerHTML = '';
    }

    for (const pub of this.room.localParticipant.trackPublications.values()) {
      if (pub.track && pub.track.kind === 'video' && !pub.isMuted) {
        this._attachLocalTrack(pub.track);
      }
    }

    for (const participant of this.room.remoteParticipants.values()) {
      for (const pub of participant.trackPublications.values()) {
        if (!pub.track || pub.track.kind !== 'video' || pub.isMuted) continue;
        this._attachRemoteTrack(pub.track, participant);
      }
    }

    this._syncLocalVideoState();
    this._checkRemoteTracks();
  }

  /**
   * Aligns isVideoOff / isScreenShareOn with what is actually published.
   *
   * These used to be plain toggle flags, so after a reconnect that failed to
   * republish the camera the button still claimed the camera was on while no
   * track existed.
   */
  _syncLocalVideoState() {
    if (!this.room) return;
    const local = this.room.localParticipant;
    const cameraPub = local.getTrackPublication('camera');
    const screenPub = local.getTrackPublication('screen_share');
    this.isVideoOff = !(cameraPub && cameraPub.track && !cameraPub.isMuted);
    this.isScreenShareOn = !!(screenPub && screenPub.track && !screenPub.isMuted);
    this._notifyMediaState();
  }

  /**
   * Re-enables the camera if it was supposed to be on but no track survived the
   * reconnect. LiveKit's republish silently swallows a failed getUserMedia, so
   * without this retry the camera stays gone for the rest of the call.
   */
  async _restoreCameraIfNeeded(wantCamera) {
    if (!wantCamera || !this.room) return;
    const pub = this.room.localParticipant.getTrackPublication('camera');
    if (pub && pub.track && !pub.isMuted) return;
    try {
      await this.room.localParticipant.setCameraEnabled(true);
    } catch (e) {
      console.warn('Failed to restore camera after reconnect:', e);
      showToast('Не удалось восстановить камеру', 'error');
    }
    this._syncLocalVideoState();
  }

  _notifyState() {
    if (typeof this.onCallStateChange === 'function') {
      this.onCallStateChange(this.currentCall, this._mediaState());
    }
  }

  _notifyMediaState() {
    if (typeof this.onMediaStateChange === 'function') {
      this.onMediaStateChange(this._mediaState());
    }
  }

  _mediaState() {
    return {
      isMuted: this.isMuted,
      isVideoOff: this.isVideoOff,
      isScreenShareOn: this.isScreenShareOn,
      hasRemoteVideo: this.hasRemoteVideo,
      isReconnecting: this.isReconnecting,
      peerOnline: this.peerOnline,
    };
  }
}

export const callManager = new CallManager();

