// Desktop client integration module (Wails v2 bridge)

export function isDesktop() {
  if (typeof window === 'undefined') return false;
  if (window.__PENIK_DESKTOP__) return true;
  if (window.go && window.go.main && window.go.main.App) return true;
  if (window.location.protocol === 'wails:' || window.location.hostname === 'wails.localhost') return true;
  return false;
}

export function isWindowsDesktop() {
  if (!isDesktop()) return false;
  if (typeof window !== 'undefined' && window.__PENIK_DESKTOP_PLATFORM__) {
    return window.__PENIK_DESKTOP_PLATFORM__ === 'windows';
  }
  if (typeof navigator !== 'undefined') {
    const ua = (navigator.userAgent || '').toLowerCase();
    const platform = (navigator.platform || '').toLowerCase();
    if (ua.includes('windows') || platform.includes('win')) return true;
  }
  return false;
}

export async function initDesktop() {
  if (!isDesktop()) return;

  window.__PENIK_DESKTOP__ = true;

  // Listen to Wails notification click events to navigate to corresponding chat
  if (window.runtime && typeof window.runtime.EventsOn === 'function') {
    window.runtime.EventsOn('desktop:notification_clicked', (tag) => {
      if (typeof tag === 'string') {
        if (tag.startsWith('chat_')) {
          const userId = tag.replace('chat_', '');
          window.location.hash = `#/chat/${userId}`;
        } else if (tag.startsWith('group_')) {
          const groupId = tag.replace('group_', '');
          window.location.hash = `#/group/${groupId}`;
        }
      }
    });
  }

  // If Wails App bindings are available, sync server URL and platform info
  if (window.go && window.go.main && window.go.main.App) {
    try {
      if (typeof window.go.main.App.GetPlatform === 'function') {
        window.__PENIK_DESKTOP_PLATFORM__ = await window.go.main.App.GetPlatform();
      }
      const serverUrl = await window.go.main.App.GetServerURL();
      if (serverUrl && !window.__PENIK_API_ORIGIN__) {
        window.__PENIK_API_ORIGIN__ = serverUrl;
      }
    } catch (e) {
      console.warn('[desktop] Failed to get server URL from bridge:', e);
    }
  }
}

export async function getDesktopVersionInfo() {
  if (!isDesktop() || !window.go?.main?.App?.GetVersionInfo) {
    return null;
  }
  try {
    return await window.go.main.App.GetVersionInfo();
  } catch (e) {
    return null;
  }
}

export async function setDesktopServerURL(url) {
  if (!isDesktop() || !window.go?.main?.App?.SetServerURL) {
    return false;
  }
  try {
    await window.go.main.App.SetServerURL(url);
    window.__PENIK_API_ORIGIN__ = url;
    if (window.localStorage) {
      window.localStorage.setItem('penik_api_origin', url);
    }
    return true;
  } catch (e) {
    console.error('[desktop] Failed to set server URL:', e);
    return false;
  }
}

export async function sendDesktopNotification(title, body, tag = '', avatarUrl = '') {
  if (!isDesktop() || !window.go?.main?.App?.Notify) {
    // Fallback to Web Notification API if permitted
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      try {
        const notif = new Notification(title, {
          body,
          icon: avatarUrl || '/assets/favicon-32x32.png',
          tag
        });
        if (tag) {
          notif.onclick = () => {
            window.focus();
            if (tag.startsWith('chat_')) {
              window.location.hash = `#/chat/${tag.replace('chat_', '')}`;
            } else if (tag.startsWith('group_')) {
              window.location.hash = `#/group/${tag.replace('group_', '')}`;
            }
            notif.close();
          };
        }
        return true;
      } catch (e) {
        return false;
      }
    }
    return false;
  }

  try {
    await window.go.main.App.Notify(title, body, tag, avatarUrl || '');
    return true;
  } catch (e) {
    console.warn('[desktop] Notification error:', e);
    return false;
  }
}

export async function openDesktopFileDialog(title, filters = []) {
  if (!isDesktop() || !window.go?.main?.App?.OpenFileDialog) {
    return null;
  }
  try {
    const path = await window.go.main.App.OpenFileDialog(title || 'Выберите файл', filters);
    return path || null;
  } catch (e) {
    console.error('[desktop] OpenFileDialog error:', e);
    return null;
  }
}

export async function saveDesktopFileDialog(title, defaultFilename, filters = []) {
  if (!isDesktop() || !window.go?.main?.App?.SaveFileDialog) {
    return null;
  }
  try {
    const path = await window.go.main.App.SaveFileDialog(title || 'Сохранить файл', defaultFilename || '', filters);
    return path || null;
  } catch (e) {
    console.error('[desktop] SaveFileDialog error:', e);
    return null;
  }
}

export async function writeDesktopFile(filePath, base64Data) {
  if (!isDesktop() || !window.go?.main?.App?.SaveFile) {
    return false;
  }
  try {
    await window.go.main.App.SaveFile(filePath, base64Data);
    return true;
  } catch (e) {
    console.error('[desktop] SaveFile error:', e);
    return false;
  }
}

export async function readDesktopFile(filePath) {
  if (!isDesktop() || !window.go?.main?.App?.ReadFile) {
    return null;
  }
  try {
    return await window.go.main.App.ReadFile(filePath);
  } catch (e) {
    console.error('[desktop] ReadFile error:', e);
    return null;
  }
}

export async function desktopFetch(method, url, headers = {}, body = '') {
  if (!isDesktop() || !window.go?.main?.App?.HttpRequest) {
    return null;
  }
  try {
    return await window.go.main.App.HttpRequest(method, url, headers, typeof body === 'string' ? body : JSON.stringify(body));
  } catch (e) {
    console.error('[desktop] HttpRequest bridge error:', e);
    return null;
  }
}

export async function desktopBinaryFetch(method, url, headers = {}, body = '') {
  if (!isDesktop() || !window.go?.main?.App?.HttpBinaryRequest) {
    return null;
  }
  try {
    return await window.go.main.App.HttpBinaryRequest(method, url, headers, typeof body === 'string' ? body : JSON.stringify(body));
  } catch (e) {
    console.error('[desktop] HttpBinaryRequest bridge error:', e);
    return null;
  }
}

export async function getDesktopCaptureSources() {
  if (!isDesktop() || !window.go?.main?.App?.GetCaptureSources) {
    return [];
  }
  try {
    return (await window.go.main.App.GetCaptureSources()) || [];
  } catch (e) {
    console.error('[desktop] GetCaptureSources error:', e);
    return [];
  }
}

export async function startDesktopScreenCapture(sourceId) {
  if (!isDesktop() || !window.go?.main?.App?.StartScreenCapture) {
    return null;
  }
  try {
    return await window.go.main.App.StartScreenCapture(sourceId);
  } catch (e) {
    console.error('[desktop] StartScreenCapture error:', e);
    return null;
  }
}

export async function stopDesktopScreenCapture() {
  if (!isDesktop() || !window.go?.main?.App?.StopScreenCapture) {
    return;
  }
  try {
    await window.go.main.App.StopScreenCapture();
  } catch (e) {
    console.error('[desktop] StopScreenCapture error:', e);
  }
}

export async function isNativeCallSupported() {
  return isDesktop() && typeof window.go?.main?.App?.NativeCallConnect === 'function';
}

export async function nativeCallConnect(url, token, isVideo = false) {
  if (!window.go?.main?.App?.NativeCallConnect) {
    throw new Error('Native call is not supported');
  }
  return await window.go.main.App.NativeCallConnect(url, token, isVideo);
}

export async function nativeCallDisconnect() {
  if (!window.go?.main?.App?.NativeCallDisconnect) return;
  try {
    await window.go.main.App.NativeCallDisconnect();
  } catch (e) {
    console.warn('[desktop] NativeCallDisconnect error:', e);
  }
}

export async function nativeCallSetMute(muted) {
  if (!window.go?.main?.App?.NativeCallSetMute) return;
  try {
    await window.go.main.App.NativeCallSetMute(muted);
  } catch (e) {
    console.warn('[desktop] NativeCallSetMute error:', e);
  }
}


