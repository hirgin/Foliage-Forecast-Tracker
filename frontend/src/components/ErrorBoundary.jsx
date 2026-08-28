import { Component } from 'react';

/**
 * A rendering failure should not leave a blank page.
 *
 * The map runs WebGL through deck.gl, which can fail outright on machines
 * without hardware acceleration — exactly the case where a white screen with
 * no explanation is least helpful.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('Render failed:', error, info);
  }

  render() {
    if (!this.state.error) return this.props.children;

    return (
      <div className="crash">
        <h2>Something broke while rendering.</h2>
        <p>
          The map needs WebGL. If your browser has hardware acceleration
          disabled, that is the most likely cause.
        </p>
        <pre>{String(this.state.error?.message ?? this.state.error)}</pre>
        <button onClick={() => window.location.reload()}>Reload</button>
      </div>
    );
  }
}
