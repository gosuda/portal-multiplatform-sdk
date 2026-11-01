export function mountReaction(root) {
  root.innerHTML = `
    <h2>Reaction test</h2>
    <p class="hint">Wait until the pad says GO, then react. Click, tap, or press Space / Enter while the pad is focused. Clicking early does not count.</p>
    <div class="metrics">
      <div class="metric">Last<strong data-last>—</strong></div>
      <div class="metric">Best<strong data-best>—</strong></div>
      <div class="metric">Median of last 5<strong data-median>—</strong></div>
    </div>
    <button class="reaction-pad" data-state="idle" aria-describedby="reaction-status"><strong data-title>Ready when you are</strong><span data-instruction>Tap to start</span></button>
    <p id="reaction-status" class="status" role="status">No round in progress.</p>
    <p class="reaction-history" data-history>No successful trials yet.</p>
    <p class="hint">Times include your browser, input device and display latency. This is a game, not a calibrated measurement. Results stay in this tab.</p>`;
  const pad = root.querySelector('.reaction-pad');
  const title = root.querySelector('[data-title]');
  const instruction = root.querySelector('[data-instruction]');
  const status = root.querySelector('.status');
  const last = root.querySelector('[data-last]');
  const best = root.querySelector('[data-best]');
  const median = root.querySelector('[data-median]');
  const history = root.querySelector('[data-history]');
  let state = 'idle';
  let timer = null;
  let frame = null;
  let startTime = 0;
  let bestTime = Infinity;
  const trials = [];
  function cancelPending() {
    clearTimeout(timer);
    if (frame !== null) cancelAnimationFrame(frame);
    timer = frame = null;
  }
  function setState(next, headline, detail, message) {
    state = next;
    pad.dataset.state = next;
    title.textContent = headline;
    instruction.textContent = detail;
    status.textContent = message;
  }
  function begin() {
    cancelPending();
    setState('waiting', 'Wait for GO…', 'Do not tap yet', 'Waiting. The delay changes every round.');
    timer = setTimeout(() => {
      timer = null;
      frame = requestAnimationFrame(() => {
        frame = null;
        if (state !== 'waiting' || document.hidden) return;
        startTime = performance.now();
        setState('go', 'GO!', 'Tap now', 'GO. React now.');
      });
    }, 1200 + Math.random() * 2300);
  }
  function react() {
    if (state === 'waiting') {
      cancelPending();
      setState('early', 'Too soon', 'Tap to try again', 'False start. Wait for the GO signal.');
    } else if (state === 'go') {
      const elapsed = performance.now() - startTime;
      bestTime = Math.min(bestTime, elapsed);
      trials.push(elapsed);
      if (trials.length > 5) trials.shift();
      const sorted = [...trials].sort((a, b) => a - b);
      const middle = Math.floor(sorted.length / 2);
      const medianTime = sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
      last.textContent = `${Math.round(elapsed)} ms`;
      best.textContent = `${Math.round(bestTime)} ms`;
      median.textContent = `${Math.round(medianTime)} ms`;
      history.textContent = `Recent trials: ${trials.map(value => `${Math.round(value)} ms`).join(' · ')}`;
      setState('result', `${Math.round(elapsed)} ms`, 'Tap for another round', 'Successful reaction recorded.');
    } else begin();
  }
  pad.addEventListener('pointerdown', event => {
    if (event.button !== 0 || !event.isPrimary) return;
    event.preventDefault();
    pad.focus({ preventScroll: true });
    react();
  });
  pad.addEventListener('keydown', event => {
    if (event.key !== ' ' && event.key !== 'Enter') return;
    event.preventDefault();
    if (!event.repeat) react();
  });
  // Assistive technologies can activate a native button without pointer or key events.
  pad.addEventListener('click', event => { if (event.detail === 0 && !event.pointerType) react(); });
  return {
    deactivate() {
      cancelPending();
      if (state === 'waiting' || state === 'go') {
        setState('idle', 'Round paused', 'Tap to start again', 'Leaving the game cancelled the current round.');
      }
    }
  };
}
