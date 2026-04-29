
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import ShareLandingPage from './components/ShareLandingPage';

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error("Could not find root element to mount to");
}

const root = ReactDOM.createRoot(rootElement);
root.render(
  <React.StrictMode>
    {window.location.pathname === '/share' ? <ShareLandingPage /> : <App />}
  </React.StrictMode>
);
