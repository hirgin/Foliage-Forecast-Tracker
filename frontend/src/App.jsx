import { lazy, Suspense } from 'react';
import { useHashRoute } from './routing';
import Nav from './components/Nav';
import ErrorBoundary from './components/ErrorBoundary';
import HowItWorks from './pages/HowItWorks';
import AboutTheBuild from './pages/AboutTheBuild';

// deck.gl and h3-js are the overwhelming majority of the bundle, and neither
// content page needs them. Splitting the map out means a reader who lands on
// "How it works" downloads a fraction of the JavaScript.
const MapView = lazy(() => import('./pages/MapView'));

export default function App() {
  const route = useHashRoute();
  const nav = <Nav route={route} />;

  return (
    <ErrorBoundary>
      {route === '' ? (
        <Suspense fallback={<div className="booting">Loading map…</div>}>
          <MapView nav={nav} />
        </Suspense>
      ) : route === 'how-it-works' ? (
        <HowItWorks nav={nav} />
      ) : (
        <AboutTheBuild nav={nav} />
      )}
    </ErrorBoundary>
  );
}
