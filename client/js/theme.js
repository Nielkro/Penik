// Theme management: persists the user's light/dark choice and applies it to the
// document root so the CSS variable palette in main.css switches accordingly.

const THEME_KEY = 'penik_theme';

/** @returns {'dark'|'light'} */
export function getTheme() {
  const t = localStorage.getItem(THEME_KEY);
  return t === 'light' ? 'light' : 'dark';
}

/** @param {'dark'|'light'} theme */
export function applyTheme(theme) {
  const root = document.documentElement;
  if (theme === 'light') {
    root.setAttribute('data-theme', 'light');
  } else {
    root.removeAttribute('data-theme');
  }
}

/** @param {'dark'|'light'} theme */
export function setTheme(theme) {
  const t = theme === 'light' ? 'light' : 'dark';
  localStorage.setItem(THEME_KEY, t);
  applyTheme(t);
}

/** Toggles between dark and light and returns the new theme. */
export function toggleTheme() {
  const next = getTheme() === 'light' ? 'dark' : 'light';
  setTheme(next);
  return next;
}

// initTheme applies the saved theme as early as possible during boot.
export function initTheme() {
  applyTheme(getTheme());
}
