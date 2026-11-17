// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import Dialog from '../../../src/components/ui/tsx/Dialog';

function EditableDialog() {
  const [value, setValue] = useState('');

  return (
    <Dialog open title="Thêm thông tin" onClose={() => {}}>
      <input
        aria-label="Thông tin"
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
    </Dialog>
  );
}

describe('Dialog', () => {
  it('keeps focus in an input while its value causes rerenders', () => {
    render(<EditableDialog />);
    const input = screen.getByRole('textbox', { name: 'Thông tin' });
    input.focus();

    fireEvent.change(input, { target: { value: 'A' } });
    expect(document.activeElement).toBe(input);

    fireEvent.change(input, { target: { value: 'AB' } });
    expect(input).toHaveProperty('value', 'AB');
    expect(document.activeElement).toBe(input);
  });
});
