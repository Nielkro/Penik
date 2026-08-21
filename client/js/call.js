import { Room, RoomEvent, VideoPresets } from 'livekit-client';
import { ws, OP } from './ws.js';
import { showToast } from './ui/components.js';

export class CallManager {
  constructor() {
    this.currentCall = null;
    this.room = null;
    this.isMuted = false;
    this.isVideoOff = false;

    this.onCallStateChange = null;
    this.onMediaStateChange = null;
  }

  init() {
    ws.on(OP.CALL_INCOMING, (payload) => this._handleIncomingCall(payload));
    ws.on(OP.CALL_ACCEPTED, (payload) => this._handleCallAccepted(payload));
    ws.on(OP.CALL_REJECT, (payload) => this._handleCallReject(payload));
    ws.on(OP.CALL_END, () => this._handleCallEnd());
  }

  startCall(toUserId, isVideo = false) {
    if (this.currentCall) {
      showToast('Вы уже находитесь в звонке', 'error');
      return;
    }

    this.currentCall = {
      state: 'DIALING',
      toUserId,
      isVideo,
      callId: null,
    };

    ws.send(OP.CALL_OFFER, {
      to_user_id: toUserId,
      is_video: isVideo,
    });

    this._notifyState();
  }

  acceptCall() {
    if (!this.currentCall || this.currentCall.state !== 'INCOMING') return;

    const { callId, token, livekitUrl, livekitFallbackUrl } = this.currentCall;
    this.currentCall.state = 'CONNECTING';
    this._notifyState();

    ws.send(OP.CALL_ACCEPT, { call_id: callId });

    this._connectLiveKit(livekitUrl, livekitFallbackUrl, token);
  }

  rejectCall(reason = 'declined') {
    if (!this.currentCall) return;

    ws.send(OP.CALL_REJECT, {
      call_id: this.currentCall.callId || '',
      to_user_id: this.currentCall.toUserId || this.currentCall.fromUserId,
      reason,
    });

    this.cleanup();
  }

  endCall() {
    if (!this.currentCall) return;

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
    this.isVideoOff = !this.isVideoOff;
    await this.room.localParticipant.setCameraEnabled(!this.isVideoOff);
    this._notifyMediaState();
  }

  cleanup() {
    if (this.room) {
      try {
        this.room.disconnect();
      } catch (e) {
        console.error(e);
      }
      this.room = null;
    }

    this.currentCall = null;
    this.isMuted = false;
    this.isVideoOff = false;
    this._notifyState();
  }

  _handleIncomingCall(payload) {
    if (this.currentCall) {
      ws.send(OP.CALL_REJECT, {
        call_id: payload.call_id,
        to_user_id: payload.from_user_id,
        reason: 'busy',
      });
      return;
    }

    this.currentCall = {
      state: 'INCOMING',
      callId: payload.call_id,
      fromUserId: payload.from_user_id,
      isVideo: payload.is_video,
      roomName: payload.room_name,
      livekitUrl: payload.livekit_url,
      livekitFallbackUrl: payload.livekit_fallback_url,
      token: payload.token,
    };

    this._notifyState();
  }

  _handleCallAccepted(payload) {
    if (!this.currentCall || this.currentCall.state !== 'DIALING') return;

    this.currentCall.callId = payload.call_id;
    this.currentCall.state = 'CONNECTING';
    this._notifyState();

    this._connectLiveKit(payload.livekit_url, payload.livekit_fallback_url, payload.token);
  }

  _handleCallReject(payload) {
    if (!this.currentCall) return;

    const reason = payload.reason === 'busy' ? 'Пользователь занят' : 'Звонок отклонен';
    showToast(reason, 'info');
    this.cleanup();
  }

  _handleCallEnd() {
    if (!this.currentCall) return;
    showToast('Звонок завершен', 'info');
    this.cleanup();
  }

  async _connectLiveKit(primaryUrl, fallbackUrl, token) {
    // Legacy call signature check (primaryUrl, token)
    if (!token && typeof fallbackUrl === 'string' && !fallbackUrl.startsWith('ws://') && !fallbackUrl.startsWith('wss://')) {
      token = fallbackUrl;
      fallbackUrl = null;
    }

    const urlsToTry = [primaryUrl];
    if (fallbackUrl && fallbackUrl !== primaryUrl) {
      urlsToTry.push(fallbackUrl);
    }

    let lastErr = null;
    for (const url of urlsToTry) {
      try {
        this.room = new Room({
          adaptiveStream: {
            pixelDensity: 2,
          },
          dynacast: true,
          audioCaptureDefaults: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
          },
          videoCaptureDefaults: {
            resolution: { width: 1920, height: 1080 },
            frameRate: 60,
          },
          publishDefaults: {
            videoCodec: 'vp9',
            backupCodec: { codec: 'vp8' },
            videoEncoding: {
              maxBitrate: 6_000_000, // 6 Mbps Full HD 60FPS
              maxFramerate: 60,
            },
            videoSimulcastLayers: [
              VideoPresets.h720,
              VideoPresets.h540,
            ],
            audioPreset: {
              maxBitrate: 128_000, // 128 kbps HD Audio
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
          .on(RoomEvent.TrackUnsubscribed, (track) => {
            track.detach();
          })
          .on(RoomEvent.LocalTrackPublished, (publication) => {
            if (publication.track) {
              this._attachLocalTrack(publication.track);
            }
          })
          .on(RoomEvent.Disconnected, () => {
            this.cleanup();
          });

        await this.room.connect(url, token);

        if (this.currentCall) {
          this.currentCall.state = 'ACTIVE';
          this._notifyState();
        }

        await this.room.localParticipant.setMicrophoneEnabled(true);
        if (this.currentCall && this.currentCall.isVideo) {
          await this.room.localParticipant.setCameraEnabled(true);
        }
        return; // Successfully connected
      } catch (err) {
        console.warn(`Failed to connect to LiveKit URL ${url}:`, err);
        lastErr = err;
        if (this.room) {
          try { this.room.disconnect(); } catch (_) {}
          this.room = null;
        }
      }
    }

    console.error('LiveKit connection error across all endpoints:', lastErr);
    showToast('Ошибка подключения к серверу звонка', 'error');
    this.cleanup();
  }

  _attachRemoteTrack(track, participant) {
    const container = document.getElementById('remote-video-container');
    if (!container) return;

    const element = track.attach();
    element.dataset.participantId = participant.identity;
    element.className = 'remote-video-element';
    container.appendChild(element);
  }

  _attachLocalTrack(track) {
    if (track.kind !== 'video') return;
    const container = document.getElementById('local-video-container');
    if (!container) return;

    container.innerHTML = '';
    const element = track.attach();
    element.className = 'local-video-element';
    element.muted = true;
    container.appendChild(element);
  }

  _notifyState() {
    if (typeof this.onCallStateChange === 'function') {
      this.onCallStateChange(this.currentCall, {
        isMuted: this.isMuted,
        isVideoOff: this.isVideoOff,
      });
    }
  }

  _notifyMediaState() {
    if (typeof this.onMediaStateChange === 'function') {
      this.onMediaStateChange({
        isMuted: this.isMuted,
        isVideoOff: this.isVideoOff,
      });
    }
  }
}

export const callManager = new CallManager();
