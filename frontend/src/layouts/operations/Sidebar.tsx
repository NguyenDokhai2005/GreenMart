import { useState } from 'react';
import { NavLink } from 'react-router';
import { Icon } from './Icon';
import type { MenuItem } from './menuConfig';

function Group({ item }: { item: MenuItem }) {
  const [open, setOpen] = useState(true);
  const id = `ops-sub-${item.key}`;
  return (
    <li>
      <button
        type="button"
        className="ops-nav__item ops-nav__group"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((o) => !o)}
      >
        {item.icon && <Icon name={item.icon} />}
        <span>{item.label}</span>
        <Icon name="chevron" size={14} className={open ? 'ops-flip' : undefined} />
      </button>
      {open && (
        <ul id={id} className="ops-nav__sub">
          {item.children!.map((c) => (
            <li key={c.key}>
              <NavLink to={c.to!} className="ops-nav__subitem">
                {c.label}
              </NavLink>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

export function Sidebar({ items }: { items: MenuItem[] }) {
  return (
    <aside className="ops-sidebar">
      <div className="ops-brand">
        <strong>GreenMart</strong>
        <span>OPERATIONS</span>
      </div>
      <nav aria-label="Menu Operations">
        <ul className="ops-nav">
          {items.map((i) =>
            i.children ? (
              i.children.length > 0 && <Group key={i.key} item={i} />
            ) : (
              <li key={i.key}>
                <NavLink
                  to={i.to!}
                  end={i.to === '/operations' || i.to === '/admin'}
                  className="ops-nav__item"
                >
                  {i.icon && <Icon name={i.icon} />}
                  <span>{i.label}</span>
                </NavLink>
              </li>
            ),
          )}
        </ul>
      </nav>
      <div className="ops-sidebar__foot">
        <a href="/support" className="ops-nav__item">
          <Icon name="help" />
          <span>Trung tâm hỗ trợ</span>
        </a>
        <small>GreenMart Operations · v1.0</small>
      </div>
    </aside>
  );
}
