import { useId, useRef, useState } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import '../css/Tabs.css';

export interface TabItem {
  id: string;
  label: string;
  content: ReactNode;
  disabled?: boolean;
}

export interface TabsProps {
  tabs: TabItem[];
  defaultActiveId?: string;
  activeId?: string;
  onChange?: (id: string) => void;
  ariaLabel?: string;
  className?: string;
}

export default function Tabs({
  tabs,
  defaultActiveId,
  activeId,
  onChange,
  ariaLabel = 'Tabs',
  className = '',
}: TabsProps) {
  const generatedId = useId();
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);

  const firstEnabledTab = tabs.find((tab) => !tab.disabled);
  const initialId =
    defaultActiveId && tabs.some((tab) => tab.id === defaultActiveId && !tab.disabled)
      ? defaultActiveId
      : (firstEnabledTab?.id ?? '');

  const [internalActiveId, setInternalActiveId] = useState(initialId);

  const isControlled = activeId !== undefined;
  const selectedId = isControlled ? activeId : internalActiveId;

  const selectedTab = tabs.find((tab) => tab.id === selectedId && !tab.disabled) ?? firstEnabledTab;

  const handleSelect = (id: string) => {
    if (!isControlled) {
      setInternalActiveId(id);
    }
    onChange?.(id);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>, currentIndex: number) => {
    const enabledIndices = tabs
      .map((tab, index) => (!tab.disabled ? index : -1))
      .filter((index) => index >= 0);

    if (enabledIndices.length === 0) return;

    const currentPosition = enabledIndices.indexOf(currentIndex);
    let nextIndex: number | undefined;

    switch (event.key) {
      case 'ArrowRight':
        nextIndex = enabledIndices[(currentPosition + 1) % enabledIndices.length];
        break;
      case 'ArrowLeft':
        nextIndex =
          enabledIndices[(currentPosition - 1 + enabledIndices.length) % enabledIndices.length];
        break;
      case 'Home':
        nextIndex = enabledIndices[0];
        break;
      case 'End':
        nextIndex = enabledIndices[enabledIndices.length - 1];
        break;
      default:
        return;
    }

    event.preventDefault();

    if (nextIndex !== undefined) {
      const nextTab = tabs[nextIndex];
      handleSelect(nextTab.id);
      tabRefs.current[nextIndex]?.focus();
    }
  };

  if (tabs.length === 0) {
    return null;
  }

  return (
    <div className={`gm-tabs ${className}`.trim()}>
      <div className="gm-tabs__list" role="tablist" aria-label={ariaLabel}>
        {tabs.map((tab, index) => {
          const isActive = tab.id === selectedTab?.id;
          const tabDomId = `${generatedId}-tab-${tab.id}`;
          const panelDomId = `${generatedId}-panel-${tab.id}`;

          return (
            <button
              key={tab.id}
              ref={(element) => {
                tabRefs.current[index] = element;
              }}
              id={tabDomId}
              type="button"
              role="tab"
              className={['gm-tabs__tab', isActive ? 'gm-tabs__tab--active' : '']
                .filter(Boolean)
                .join(' ')}
              aria-selected={isActive}
              aria-controls={panelDomId}
              tabIndex={isActive ? 0 : -1}
              disabled={tab.disabled}
              onClick={() => handleSelect(tab.id)}
              onKeyDown={(event) => handleKeyDown(event, index)}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {selectedTab && (
        <div
          id={`${generatedId}-panel-${selectedTab.id}`}
          className="gm-tabs__panel"
          role="tabpanel"
          aria-labelledby={`${generatedId}-tab-${selectedTab.id}`}
          tabIndex={0}
        >
          {selectedTab.content}
        </div>
      )}
    </div>
  );
}
