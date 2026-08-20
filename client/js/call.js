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

    const { callId, token, livekitUrl } = this.currentCall;
    this.currentCall.state = 'CONNECTING';
    this._notifyState();

    ws.send(OP.CALL_ACCEPT, { call_id: callId });

    this._connectLiveKit(livekitUrl, token);
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
      token: payload.token,
    };

    this._notifyState();
  }

  _handleCallAccepted(payload) {
    if (!this.currentCall || this.currentCall.state !== 'DIALING') return;

    this.currentCall.callId = payload.call_id;
    this.currentCall.state = 'CONNECTING';
    this._notifyState();

    this._connectLiveKit(payload.livekit_url, payload.token);
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

  async _connectLiveKit(url, token) {
    try {
      this.room = new Room({
        adaptiveStream: false,
        dynacast: false,
        videoCaptureDefaults: {
          resolution: VideoPresets.h1080.resolution,
        },
        publishDefaults: {
          videoEncoding: VideoPresets.h1080.encoding,
          simulcast: false,
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

      await this.room.connect(url, token, {
        rtcConfig: {
          iceServers: [
            {
              urls: 'turn:188.234.237.181:3478?transport=udp',
              username: 'XqMHIp1ngpZmmv29vgusTONyrAiI7/7ZM0YeVgtS2Ec=',
              credential: '13f85af63411137a848c5d8c5ab90a3de048b72073c3c9c5f61f62128a929784',
            },
            {
              urls: 'stun:stun.l.google.com:19302',
            },
          ],
        },
      });

      if (this.currentCall) {
        this.currentCall.state = 'ACTIVE';
        this._notifyState();
      }

      await this.room.localParticipant.setMicrophoneEnabled(true);
      if (this.currentCall && this.currentCall.isVideo) {
        await this.room.localParticipant.setCameraEnabled(true);
      }
    } catch (err) {
      console.error('LiveKit connection error:', err);
      showToast('Ошибка подключения к серверу звонка', 'error');
      this.cleanup();
    }
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
