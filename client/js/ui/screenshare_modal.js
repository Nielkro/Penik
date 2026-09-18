import { getDesktopCaptureSources } from '../desktop.js';

let activePickerResolver = null;

export async function openScreenPickerModal() {
  return new Promise(async (resolve) => {
    // Remove existing modal if any
    const existing = document.getElementById('screen-picker-modal');
    if (existing) existing.remove();

    const sources = await getDesktopCaptureSources();
    if (!sources || sources.length === 0) {
      resolve(null);
      return;
    }

    activePickerResolver = resolve;

    const screens = sources.filter((s) => s.type === 'screen');
    const windows = sources.filter((s) => s.type === 'window');

    let currentTab = screens.length > 0 ? 'screen' : 'window';
    let selectedSource = (currentTab === 'screen' ? screens[0] : windows[0]) || sources[0];

    const overlay = document.createElement('div');
    overlay.id = 'screen-picker-modal';
    overlay.className = 'modal-overlay active screen-picker-overlay';

    const renderSourcesGrid = (items) => {
      if (items.length === 0) {
        return `<div class="screen-picker-empty">Нет доступных источников</div>`;
      }
      return items
        .map((item) => {
          const isSelected = selectedSource?.id === item.id;
          const thumbSrc = item.thumbnail || '/assets/favicon-32x32.png';
          return `
            <div class="screen-picker-card ${isSelected ? 'selected' : ''}" data-source-id="${item.id}">
              <div class="screen-picker-thumb-wrapper">
                <img src="${thumbSrc}" class="screen-picker-thumb" alt="${item.name}" loading="lazy" />
              </div>
              <div class="screen-picker-card-info">
                <span class="screen-picker-card-title" title="${item.name}">${item.name}</span>
                <span class="screen-picker-card-res">${item.width} × ${item.height}</span>
              </div>
            </div>
          `;
        })
        .join('');
    };

    overlay.innerHTML = `
      <div class="modal-card screen-picker-card-container">
        <div class="screen-picker-header">
          <h3>Демонстрация экрана</h3>
          <button class="screen-picker-close-btn" id="btn-close-screen-picker">✕</button>
        </div>
        <div class="screen-picker-tabs">
          <button class="screen-picker-tab ${currentTab === 'screen' ? 'active' : ''}" id="tab-screens">
            Экраны (${screens.length})
          </button>
          <button class="screen-picker-tab ${currentTab === 'window' ? 'active' : ''}" id="tab-windows">
            Приложения (${windows.length})
          </button>
        </div>
        <div class="screen-picker-grid" id="screen-picker-grid">
          ${renderSourcesGrid(currentTab === 'screen' ? screens : windows)}
        </div>
        <div class="screen-picker-footer">
          <button class="btn btn-secondary" id="btn-cancel-screen-picker">Отмена</button>
          <button class="btn btn-primary" id="btn-confirm-screen-picker">Поделиться</button>
        </div>
      </div>
    `;

    document.body.appendChild(overlay);

    const updateGrid = () => {
      const grid = document.getElementById('screen-picker-grid');
      if (grid) {
        grid.innerHTML = renderSourcesGrid(currentTab === 'screen' ? screens : windows);
        bindCardClicks();
      }
    };

    const bindCardClicks = () => {
      const cards = overlay.querySelectorAll('.screen-picker-card');
      cards.forEach((card) => {
        card.addEventListener('click', () => {
          cards.forEach((c) => c.classList.remove('selected'));
          card.classList.add('selected');
          const id = card.getAttribute('data-source-id');
          selectedSource = sources.find((s) => s.id === id) || null;
        });
        card.addEventListener('dblclick', () => {
          const id = card.getAttribute('data-source-id');
          selectedSource = sources.find((s) => s.id === id) || null;
          closeWithResult(selectedSource);
        });
      });
    };

    const closeWithResult = (result) => {
      overlay.remove();
      if (activePickerResolver) {
        activePickerResolver(result);
        activePickerResolver = null;
      }
    };

    document.getElementById('tab-screens')?.addEventListener('click', () => {
      currentTab = 'screen';
      document.getElementById('tab-screens')?.classList.add('active');
      document.getElementById('tab-windows')?.classList.remove('active');
      if (screens.length > 0 && (!selectedSource || selectedSource.type !== 'screen')) {
        selectedSource = screens[0];
      }
      updateGrid();
    });

    document.getElementById('tab-windows')?.addEventListener('click', () => {
      currentTab = 'window';
      document.getElementById('tab-windows')?.classList.add('active');
      document.getElementById('tab-screens')?.classList.remove('active');
      if (windows.length > 0 && (!selectedSource || selectedSource.type !== 'window')) {
        selectedSource = windows[0];
      }
      updateGrid();
    });

    document.getElementById('btn-close-screen-picker')?.addEventListener('click', () => closeWithResult(null));
    document.getElementById('btn-cancel-screen-picker')?.addEventListener('click', () => closeWithResult(null));
    document.getElementById('btn-confirm-screen-picker')?.addEventListener('click', () => closeWithResult(selectedSource));

    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) {
        closeWithResult(null);
      }
    });

    bindCardClicks();
  });
}
