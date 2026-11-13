import { useContext } from 'react';
import { OperationsScopeContext } from './OperationsScopeContext';

export function useOperationsScope() {
  const context = useContext(OperationsScopeContext);
  if (!context) {
    throw new Error('useOperationsScope phải được dùng bên trong OperationsScopeProvider.');
  }
  return context;
}
