import { lazy, Suspense } from 'react';
import { useHashRoute } from './routing';
import Nav from './components/Nav';
import ErrorBoundary from './components/ErrorBoundary';
import HowItWorks from './pages/HowItWorks';
import AboutTheBuild from './pages/AboutTheBuild';

// deck.gl and h3-js are the overwhelming majority of the bundle, and no other
// page needs them. Splitting the map out means a reader who lands on "How it
// works" downloads a fraction of the JavaScript. The trip planner is split for
// the same reason in reverse: it pulls the place index and the timeline
// decoders, which someone who only ever looks at the map never touches.
const MapView = lazy(() => import('./pages/MapView'));
const Trip = lazy(() => import('./pages/Trip'));

const LAZY = { '': MapView, trip: Trip };
const EAGER = { 'how-it-works': HowItWorks, 'about-the-build': AboutTheBuild };

export default function App() {
  const route = useHashRoute();
  const nav = <Nav route={route} />;
  const Split = LAZY[route];
  const Page = EAGER[route] ?? AboutTheBuild;

  return (
    <ErrorBoundary>
      {Split ? (
        <Suspense fallback={<div className="booting">Loading…</div>}>
          <Split nav={nav} />
        </Suspense>
      ) : (
        <Page nav={nav} />
      )}
    </ErrorBoundary>
  );
}
