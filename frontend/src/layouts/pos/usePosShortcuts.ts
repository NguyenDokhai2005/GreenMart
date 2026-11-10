import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { POS_SHORTCUTS, type PosNavigationItem } from './posNavigation';

export function usePosShortcuts(items: readonly PosNavigationItem[]) {
  const navigate = useNavigate();

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      const modifiers = POS_SHORTCUTS.modifiers;
      if (
        event.defaultPrevented ||
        event.repeat ||
        event.isComposing ||
        event.ctrlKey !== modifiers.ctrl ||
        event.altKey !== modifiers.alt ||
        event.shiftKey !== modifiers.shift ||
        event.metaKey !== modifiers.meta ||
        document.querySelector('[role="dialog"][aria-modal="true"], dialog[open]')
      ) {
        return;
      }

      // Do not take keystrokes from forms, barcode inputs, or editable widgets.
      if (
        event
          .composedPath()
          .some(
            (target) =>
              target instanceof HTMLElement &&
              target.matches(
                'input, textarea, select, [contenteditable]:not([contenteditable="false"]), [role="textbox"]',
              ),
          )
      ) {
        return;
      }

      // Physical codes also work when Option changes event.key on macOS.
      const item = items.find((candidate) => candidate.code === event.code);
      if (!item) return;
      event.preventDefault();
      navigate(item.path);
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [items, navigate]);
}
