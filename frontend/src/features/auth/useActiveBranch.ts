import { useContext } from 'react';
import { ActiveBranchContext } from './ActiveBranchContext';

export function useActiveBranch() {
  const context = useContext(ActiveBranchContext);
  if (!context) throw new Error('useActiveBranch phải được dùng bên trong ActiveBranchProvider.');
  return context;
}
