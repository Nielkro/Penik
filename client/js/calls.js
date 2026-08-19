import { Room, RoomEvent } from "livekit-client";
import { initiateCall, respondCall } from "./api.js";
import { el, showToast } from "./ui/components.js";

let activeRoom = null;
let currentCallId = null;
let activeCallModal = null;
let incomingCallModal = null;
let outgoingModalState = null;

// Initialize global WebSockets / custom call signal listener
export function initCallSystem() {
  window.addEventListener("penik:ws-message", (/** @type {any} */ e) => {
    const data = e.detail;
    if (!data) return;

    if (data.type === "incoming_call") {
      handleIncomingCall(data);
    } else if (data.type === "call_accepted") {
      handleCallAccepted(data);
    } else if (data.type === "call_rejected") {
      handleCallRejected();
    } else if (data.type === "call_busy") {
      handleCallBusy();
    } else if (data.type === "call_ended") {
      handleCallEnded();
    }
  });
}

export async function startCall(calleeUserId, callType = "audio") {
  try {
    showToast("Инициализация вызова…");
    const targetId = Number(calleeUserId);
    const callData = await initiateCall(targetId, callType);
    currentCallId = callData.call_id;

    showOutgoingCallModal(callData.room_name, callType, callData.token, callData.livekit_url);
  } catch (err) {
    showToast(`Ошибка вызова: ${err.message || err}`);
  }
}

function showOutgoingCallModal(roomName, callType, token, livekitUrl) {
  closeAllCallModals();

  const statusEl = el("div", { style: "margin-top: 8px; color: var(--text-muted);" }, "Звоним…");
  const cancelBtn = el("button", { class: "btn btn-danger", style: "margin-top: 16px;" }, "Отмена");

  const modal = el("div", { class: "modal-backdrop" },
    el("div", { class: "modal-content", style: "text-align: center; max-width: 360px;" },
      el("h3", {}, callType === "video" ? "📹 Видеовызов" : "📞 Аудиовызов"),
      statusEl,
      cancelBtn
    )
  );

  cancelBtn.addEventListener("click", async () => {
    if (currentCallId) {
      await respondCall(currentCallId, "end").catch(() => {});
    }
    leaveCurrentRoom();
    closeAllCallModals();
  });

  document.body.appendChild(modal);
  outgoingModalState = { modal, token, livekitUrl, callType };
}

async function handleCallAccepted(data) {
  showToast("Вызов принят!");
  if (outgoingModalState) {
    const { token, livekitUrl, callType } = outgoingModalState;
    await connectToLiveKitRoom(token, livekitUrl, callType);
  }
}

function handleCallRejected() {
  showToast("Вызов отклонён");
  leaveCurrentRoom();
  closeAllCallModals();
}

function handleCallBusy() {
  showToast("Пользователь занят");
  leaveCurrentRoom();
  closeAllCallModals();
}

function handleCallEnded() {
  showToast("Звонок завершён");
  leaveCurrentRoom();
  closeAllCallModals();
}

function handleIncomingCall(data) {
  closeAllCallModals();
  currentCallId = data.call_id;

  const title = data.call_type === "video" ? "📹 Входящий видеовызов" : "📞 Входящий вызов";
  const callerName = data.caller_name || `Пользователь #${data.caller_user_id}`;

  const acceptBtn = el("button", { class: "btn btn-primary", style: "margin-right: 12px;" }, "Ответить");
  const rejectBtn = el("button", { class: "btn btn-danger" }, "Отклонить");

  incomingCallModal = el("div", { class: "modal-backdrop" },
    el("div", { class: "modal-content", style: "text-align: center; max-width: 360px;" },
      el("h3", {}, title),
      el("div", { style: "margin-top: 8px; font-weight: 500;" }, callerName),
      el("div", { style: "margin-top: 20px; display: flex; justify-content: center;" },
        acceptBtn,
        rejectBtn
      )
    )
  );

  acceptBtn.addEventListener("click", async () => {
    try {
      const resp = await respondCall(data.call_id, "accept");
      closeAllCallModals();
      await connectToLiveKitRoom(resp.token, resp.livekit_url, data.call_type);
    } catch (err) {
      showToast(`Ошибка подключения: ${err.message || err}`);
      closeAllCallModals();
    }
  });

  rejectBtn.addEventListener("click", async () => {
    await respondCall(data.call_id, "reject").catch(() => {});
    closeAllCallModals();
  });

  document.body.appendChild(incomingCallModal);
}

async function connectToLiveKitRoom(token, url, callType) {
  closeAllCallModals();

  const room = new Room({
    adaptiveStream: true,
    dynacast: true,
  });

  activeRoom = room;

  const localVideoContainer = el("video", {
    autoplay: true,
    playsinline: true,
    muted: true,
    style: "width: 120px; height: 90px; object-fit: cover; border-radius: 8px; position: absolute; bottom: 80px; right: 16px; border: 2px solid white; background: #000;"
  });

  const remoteVideoContainer = el("video", {
    autoplay: true,
    playsinline: true,
    style: "width: 100%; height: 320px; object-fit: cover; border-radius: 12px; background: #111;"
  });

  const audioContainer = el("div", { style: "display: none;" });

  const micBtn = el("button", { class: "btn btn-secondary", style: "margin-right: 8px;" }, "🎙️ Вкл");
  const camBtn = el("button", { class: "btn btn-secondary", style: "margin-right: 8px;" }, "📹 Вкл");
  const hangupBtn = el("button", { class: "btn btn-danger" }, "Завершить");

  if (callType !== "video") {
    camBtn.style.display = "none";
    localVideoContainer.style.display = "none";
    remoteVideoContainer.style.display = "none";
  }

  activeCallModal = el("div", { class: "modal-backdrop" },
    el("div", { class: "modal-content", style: "max-width: 540px; position: relative; padding: 20px; text-align: center;" },
      el("h3", { style: "margin-bottom: 12px;" }, callType === "video" ? "📹 Видеозвонок" : "📞 Аудиозвонок"),
      remoteVideoContainer,
      localVideoContainer,
      audioContainer,
      el("div", { style: "margin-top: 16px; display: flex; justify-content: center;" },
        micBtn,
        camBtn,
        hangupBtn
      )
    )
  );

  let isMicMuted = false;
  micBtn.addEventListener("click", async () => {
    isMicMuted = !isMicMuted;
    await room.localParticipant.setMicrophoneEnabled(!isMicMuted);
    micBtn.textContent = isMicMuted ? "🎙️ Выкл" : "🎙️ Вкл";
  });

  let isCamMuted = false;
  camBtn.addEventListener("click", async () => {
    isCamMuted = !isCamMuted;
    await room.localParticipant.setCameraEnabled(!isCamMuted);
    camBtn.textContent = isCamMuted ? "📹 Выкл" : "📹 Вкл";
  });

  hangupBtn.addEventListener("click", async () => {
    if (currentCallId) {
      await respondCall(currentCallId, "end").catch(() => {});
    }
    leaveCurrentRoom();
    closeAllCallModals();
  });

  document.body.appendChild(activeCallModal);

  room.on(RoomEvent.TrackSubscribed, (track, publication, participant) => {
    const element = track.attach();
    if (track.kind === "video") {
      remoteVideoContainer.srcObject = element.srcObject;
    } else if (track.kind === "audio") {
      audioContainer.appendChild(element);
    }
  });

  room.on(RoomEvent.TrackUnsubscribed, (track) => {
    track.detach().forEach((el) => el.remove());
  });

  room.on(RoomEvent.Disconnected, () => {
    closeAllCallModals();
  });

  try {
    await room.connect(url, token);
    await room.localParticipant.setMicrophoneEnabled(true);
    if (callType === "video") {
      await room.localParticipant.setCameraEnabled(true);
      const videoTrack = Array.from(room.localParticipant.videoTrackPublications.values())[0]?.track;
      if (videoTrack) {
        localVideoContainer.srcObject = videoTrack.mediaStream;
      }
    }
  } catch (err) {
    showToast(`Ошибка подключения LiveKit: ${err.message || err}`);
    leaveCurrentRoom();
    closeAllCallModals();
  }
}

function leaveCurrentRoom() {
  if (activeRoom) {
    try {
      activeRoom.disconnect();
    } catch (e) {}
    activeRoom = null;
  }
  currentCallId = null;
  outgoingModalState = null;
}

function closeAllCallModals() {
  if (activeCallModal) {
    activeCallModal.remove();
    activeCallModal = null;
  }
  if (incomingCallModal) {
    incomingCallModal.remove();
    incomingCallModal = null;
  }
  if (outgoingModalState && outgoingModalState.modal) {
    outgoingModalState.modal.remove();
    outgoingModalState = null;
  }
}
