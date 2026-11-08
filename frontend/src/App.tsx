import { BrowserRouter } from 'react-router';
import { ActiveBranchProvider } from './features/auth/ActiveBranchProvider';
import { AuthProvider } from './features/auth/AuthProvider';
import { AppRoutes } from './features/auth/AppRoutes';

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <ActiveBranchProvider>
          <AppRoutes />
        </ActiveBranchProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
