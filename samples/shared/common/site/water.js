const CUP_WIDTH = 220;
const CUP_HEIGHT = 250;
const SAMPLE_COUNT = 45;
const STEP = 1 / 120;
const MAX_TILT = 55;
const HALF_WIDTH = CUP_WIDTH / 2;
const HALF_HEIGHT = CUP_HEIGHT / 2;
const SPACING = CUP_WIDTH / (SAMPLE_COUNT - 1);

export function mountWater(root) {
  root.innerHTML = `
    <h2>Water fill</h2>
    <div class="toolbar">
      <button type="button" class="water-pour" data-pour>Hold to pour</button>
      <button type="button" class="secondary" data-finish>Finish round</button>
      <button type="button" class="secondary" data-reset>New round</button>
    </div>
    <div class="metrics" aria-label="Water game measurements">
      <div class="metric">Fill <strong data-fill>0.0%</strong></div>
      <div class="metric">Target <strong data-target></strong></div>
      <div class="metric">Spilled <strong data-spill>0.0%</strong></div>
      <div class="metric">Best <strong data-best>—</strong></div>
    </div>
    <canvas class="game-canvas" width="640" height="470" tabindex="0"
      role="group" aria-label="Water tank. Hold Space to pour. Drag sideways or use Left and Right to tilt."></canvas>
    <label class="water-tilt">Cup tilt
      <input data-tilt type="range" min="-55" max="55" step="1" value="0" aria-label="Cup tilt in degrees">
      <output data-angle>0°</output>
    </label>
    <p class="status" data-status role="status" aria-live="polite"></p>
    <p class="hint">Match the target with as little spill as possible, then Finish. Hold Pour with a finger or mouse; release to stop. Focus the tank or Pour button and hold Space (Enter also works on Pour). Drag the tank sideways, use Left / Right while the tank is focused, or adjust the tilt slider. Spill is measured as a percentage of one full cup. Scores and best results last only in this tab.</p>
  `;

  const canvas = root.querySelector('canvas');
  const context = canvas.getContext('2d');
  const pourButton = root.querySelector('[data-pour]');
  const finishButton = root.querySelector('[data-finish]');
  const resetButton = root.querySelector('[data-reset]');
  const tiltInput = root.querySelector('[data-tilt]');
  const angleOutput = root.querySelector('[data-angle]');
  const fillOutput = root.querySelector('[data-fill]');
  const targetOutput = root.querySelector('[data-target]');
  const spillOutput = root.querySelector('[data-spill]');
  const bestOutput = root.querySelector('[data-best]');
  const status = root.querySelector('[data-status]');
  const wave = new Float64Array(SAMPLE_COUNT);
  const velocity = new Float64Array(SAMPLE_COUNT);
  const acceleration = new Float64Array(SAMPLE_COUNT);
  const depths = new Float64Array(SAMPLE_COUNT);
  const droplets = [];
  const keys = new Set();
  let volume = 0;
  let spilled = 0;
  let target = 0;
  let angle = 0;
  let baseDepth = 0;
  let best = null;
  let finished = false;
  let paused = false;
  let pointerPour = null;
  let dragPointer = null;
  let dragX = 0;
  let dragAngle = 0;
  let frame = 0;
  let previousTime = 0;
  let accumulator = 0;
  let idleTime = 0;
  let simulationTime = 0;

  const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
  const radians = () => angle * Math.PI / 180;
  const pouring = () => pointerPour !== null || keys.size > 0;
  const depthAt = (index, base, slope) => base + (index * SPACING - HALF_WIDTH) * slope + wave[index];

  function fractionAt(base, slope) {
    let area = 0;
    for (let i = 0; i < SAMPLE_COUNT; i++) {
      const weight = i === 0 || i === SAMPLE_COUNT - 1 ? 0.5 : 1;
      area += weight * clamp(depthAt(i, base, slope), 0, CUP_HEIGHT);
    }
    return area / ((SAMPLE_COUNT - 1) * CUP_HEIGHT);
  }

  function solveSurface() {
    const slope = Math.tan(radians());
    let low = -CUP_HEIGHT - HALF_WIDTH * Math.abs(slope) - 100;
    let high = CUP_HEIGHT + HALF_WIDTH * Math.abs(slope) + 100;
    for (let i = 0; i < 28; i++) {
      const middle = (low + high) / 2;
      if (fractionAt(middle, slope) < volume) low = middle;
      else high = middle;
    }
    baseDepth = (low + high) / 2;
    for (let i = 0; i < SAMPLE_COUNT; i++) {
      depths[i] = clamp(depthAt(i, baseDepth, slope), 0, CUP_HEIGHT);
    }
  }

  function worldPoint(x, y) {
    const theta = radians();
    return {
      x: 320 + x * Math.cos(theta) - y * Math.sin(theta),
      y: 270 + x * Math.sin(theta) + y * Math.cos(theta),
    };
  }

  function addSpill(amount, edge) {
    spilled += amount;
    if (amount < 0.00001) return;
    const count = Math.min(5, Math.ceil(amount * 180));
    const origin = worldPoint(edge * HALF_WIDTH, -HALF_HEIGHT);
    for (let i = 0; i < count && droplets.length < 64; i++) {
      droplets.push({
        x: origin.x,
        y: origin.y,
        vx: edge * (35 + Math.random() * 65),
        vy: -20 + Math.random() * 45,
        life: 0.45 + Math.random() * 0.4,
      });
    }
  }

  function spillOverflow() {
    const slope = Math.tan(radians());
    const leftLimit = CUP_HEIGHT + HALF_WIDTH * slope - wave[0];
    const rightLimit = CUP_HEIGHT - HALF_WIDTH * slope - wave[SAMPLE_COUNT - 1];
    const capacity = fractionAt(Math.min(leftLimit, rightLimit), slope);
    if (volume > capacity) {
      addSpill(volume - capacity, leftLimit < rightLimit ? -1 : 1);
      volume = capacity;
    }
  }

  function updateMeasurements() {
    fillOutput.textContent = `${(volume * 100).toFixed(1)}%`;
    spillOutput.textContent = `${(spilled * 100).toFixed(1)}%`;
  }

  function draw() {
    solveSurface();
    context.clearRect(0, 0, 640, 470);
    const backdrop = context.createLinearGradient(0, 0, 0, 470);
    backdrop.addColorStop(0, '#ffffff');
    backdrop.addColorStop(1, '#f5f5f5');
    context.fillStyle = backdrop;
    context.fillRect(0, 0, 640, 470);

    context.fillStyle = '#e4e4e4';
    context.beginPath();
    context.ellipse(320, 426, 180, 17, 0, 0, Math.PI * 2);
    context.fill();
    context.fillStyle = '#444444';
    context.fillRect(307, 20, 26, 29);
    context.fillStyle = '#111111';
    context.fillRect(293, 13, 54, 11);

    if (pouring() && !finished && !paused) {
      const surfaceY = 270 + (HALF_HEIGHT - baseDepth) * Math.cos(radians());
      context.strokeStyle = '#777777';
      context.lineWidth = 9;
      context.lineCap = 'round';
      context.beginPath();
      context.moveTo(320, 50);
      context.lineTo(320 + Math.sin(simulationTime * 25) * 1.5, clamp(surfaceY, 100, 393));
      context.stroke();
      context.strokeStyle = '#dddddd';
      context.lineWidth = 2;
      context.stroke();
    }

    context.save();
    context.translate(320, 270);
    context.rotate(radians());
    context.fillStyle = 'rgba(0, 0, 0, 0.025)';
    context.fillRect(-HALF_WIDTH, -HALF_HEIGHT, CUP_WIDTH, CUP_HEIGHT);
    context.save();
    context.beginPath();
    context.rect(-HALF_WIDTH, -HALF_HEIGHT, CUP_WIDTH, CUP_HEIGHT);
    context.clip();
    if (volume > 0.00001) {
      const water = context.createLinearGradient(0, -HALF_HEIGHT, 0, HALF_HEIGHT);
      water.addColorStop(0, '#aaaaaa');
      water.addColorStop(1, '#555555');
      context.fillStyle = water;
      context.beginPath();
      context.moveTo(-HALF_WIDTH, HALF_HEIGHT);
      for (let i = 0; i < SAMPLE_COUNT; i++) {
        context.lineTo(i * SPACING - HALF_WIDTH, HALF_HEIGHT - depths[i]);
      }
      context.lineTo(HALF_WIDTH, HALF_HEIGHT);
      context.closePath();
      context.fill();
      context.strokeStyle = '#222222';
      context.lineWidth = 2.5;
      context.beginPath();
      for (let i = 0; i < SAMPLE_COUNT; i++) {
        const x = i * SPACING - HALF_WIDTH;
        const y = HALF_HEIGHT - depths[i];
        if (i === 0) context.moveTo(x, y);
        else context.lineTo(x, y);
      }
      context.stroke();
    }
    context.restore();

    context.strokeStyle = 'rgba(0, 0, 0, 0.25)';
    context.lineWidth = 1;
    for (let mark = 0.25; mark < 1; mark += 0.25) {
      const y = HALF_HEIGHT - CUP_HEIGHT * mark;
      context.beginPath();
      context.moveTo(-HALF_WIDTH, y);
      context.lineTo(-HALF_WIDTH + 16, y);
      context.moveTo(HALF_WIDTH - 16, y);
      context.lineTo(HALF_WIDTH, y);
      context.stroke();
    }
    const targetY = HALF_HEIGHT - target * CUP_HEIGHT;
    context.setLineDash([6, 5]);
    context.strokeStyle = '#111111';
    context.lineWidth = 2;
    context.beginPath();
    context.moveTo(-HALF_WIDTH, targetY);
    context.lineTo(HALF_WIDTH, targetY);
    context.stroke();
    context.setLineDash([]);
    context.strokeStyle = '#222222';
    context.lineWidth = 5;
    context.lineJoin = 'round';
    context.lineCap = 'round';
    context.beginPath();
    context.moveTo(-HALF_WIDTH, -HALF_HEIGHT - 3);
    context.lineTo(-HALF_WIDTH, HALF_HEIGHT);
    context.lineTo(HALF_WIDTH, HALF_HEIGHT);
    context.lineTo(HALF_WIDTH, -HALF_HEIGHT - 3);
    context.stroke();
    context.restore();

    context.fillStyle = '#777777';
    for (const drop of droplets) {
      context.globalAlpha = clamp(drop.life * 2, 0, 1);
      context.beginPath();
      context.ellipse(drop.x, drop.y, 2.5, 4, 0, 0, Math.PI * 2);
      context.fill();
    }
    context.globalAlpha = 1;
    context.fillStyle = '#555555';
    context.font = '14px system-ui, sans-serif';
    context.textAlign = 'center';
    context.fillText('Dashed line = target when upright', 320, 454);
    updateMeasurements();
  }

  function step(dt) {
    simulationTime += dt;
    if (pouring() && !finished) {
      volume += 0.16 * dt;
      if (volume < 0.99) {
        velocity[21] += 28 * dt;
        velocity[22] += 42 * dt;
        velocity[23] += 28 * dt;
      }
    }
    let mean = 0;
    for (let i = 0; i < SAMPLE_COUNT; i++) {
      const left = wave[i === 0 ? 1 : i - 1];
      const right = wave[i === SAMPLE_COUNT - 1 ? SAMPLE_COUNT - 2 : i + 1];
      acceleration[i] = 260 * (left + right - 2 * wave[i]) - 3.4 * velocity[i] - 1.8 * wave[i];
    }
    for (let i = 0; i < SAMPLE_COUNT; i++) {
      velocity[i] += acceleration[i] * dt;
      wave[i] = clamp(wave[i] + velocity[i] * dt, -65, 65);
      mean += wave[i];
    }
    mean /= SAMPLE_COUNT;
    for (let i = 0; i < SAMPLE_COUNT; i++) wave[i] -= mean;
    spillOverflow();
    for (let i = droplets.length - 1; i >= 0; i--) {
      const drop = droplets[i];
      drop.vy += 410 * dt;
      drop.x += drop.vx * dt;
      drop.y += drop.vy * dt;
      drop.life -= dt;
      if (drop.life <= 0 || drop.y > 470) droplets.splice(i, 1);
    }
  }

  function stopFrame() {
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    previousTime = 0;
    accumulator = 0;
  }

  function animate(time) {
    frame = 0;
    if (document.hidden || paused || finished) return;
    if (!previousTime) previousTime = time;
    accumulator += Math.min((time - previousTime) / 1000, 0.05);
    previousTime = time;
    while (accumulator >= STEP) {
      step(STEP);
      accumulator -= STEP;
    }
    let energy = 0;
    for (let i = 0; i < SAMPLE_COUNT; i++) energy += Math.abs(wave[i]) + Math.abs(velocity[i]);
    const moving = pouring() || dragPointer !== null || droplets.length > 0 || energy > 0.7;
    idleTime = moving ? 0 : idleTime + 1;
    if (idleTime >= 3) {
      wave.fill(0);
      velocity.fill(0);
      spillOverflow();
      stopFrame();
    } else {
      frame = requestAnimationFrame(animate);
    }
    draw();
  }

  function wake() {
    if (finished || document.hidden) return;
    if (paused) {
      paused = false;
      status.textContent = 'Round resumed. Release Pour to stop; Finish locks your score.';
    }
    idleTime = 0;
    if (!frame) frame = requestAnimationFrame(animate);
  }

  function releaseInputs() {
    keys.clear();
    const pourPointer = pointerPour;
    const tankPointer = dragPointer;
    pointerPour = null;
    dragPointer = null;
    if (pourPointer !== null && pourButton.hasPointerCapture(pourPointer)) pourButton.releasePointerCapture(pourPointer);
    if (tankPointer !== null && canvas.hasPointerCapture(tankPointer)) canvas.releasePointerCapture(tankPointer);
    pourButton.setAttribute('aria-pressed', 'false');
  }

  function deactivate() {
    stopFrame();
    releaseInputs();
    paused = !finished;
    if (!finished) status.textContent = 'Paused. Hold Pour or adjust the tilt to resume this round.';
    draw();
  }

  function setAngle(value) {
    if (finished || document.hidden) return;
    const next = clamp(Number(value), -MAX_TILT, MAX_TILT);
    const previousSlope = Math.tan(radians());
    angle = next;
    const slopeChange = Math.tan(radians()) - previousSlope;
    if (volume > 0.001) {
      for (let i = 0; i < SAMPLE_COUNT; i++) {
        const x = i * SPACING - HALF_WIDTH;
        wave[i] = clamp(wave[i] - x * slopeChange * 0.65, -65, 65);
        velocity[i] = clamp(velocity[i] - x * slopeChange * 1.3, -220, 220);
      }
    }
    tiltInput.value = String(angle);
    angleOutput.value = `${Math.round(angle)}°`;
    wake();
    draw();
  }

  function newRound() {
    stopFrame();
    releaseInputs();
    wave.fill(0);
    velocity.fill(0);
    droplets.length = 0;
    volume = 0;
    spilled = 0;
    angle = 0;
    finished = false;
    paused = false;
    simulationTime = 0;
    idleTime = 0;
    target = (35 + Math.floor(Math.random() * 41)) / 100;
    targetOutput.textContent = `${Math.round(target * 100)}%`;
    tiltInput.value = '0';
    angleOutput.value = '0°';
    tiltInput.disabled = false;
    pourButton.disabled = false;
    finishButton.disabled = false;
    resetButton.textContent = 'New round';
    status.textContent = 'Ready. Hold Pour to fill the cup. Tilt carefully: sloshing water can spill.';
    draw();
  }

  pourButton.setAttribute('aria-pressed', 'false');
  pourButton.addEventListener('pointerdown', (event) => {
    if (finished || document.hidden || pointerPour !== null || (event.pointerType === 'mouse' && event.button !== 0)) return;
    event.preventDefault();
    pourButton.focus({ preventScroll: true });
    pointerPour = event.pointerId;
    pourButton.setPointerCapture(event.pointerId);
    pourButton.setAttribute('aria-pressed', 'true');
    wake();
  });
  function releasePour(event) {
    if (event.pointerId !== pointerPour) return;
    pointerPour = null;
    pourButton.setAttribute('aria-pressed', String(pouring()));
    draw();
  }
  pourButton.addEventListener('pointerup', releasePour);
  pourButton.addEventListener('pointercancel', releasePour);
  pourButton.addEventListener('lostpointercapture', releasePour);
  root.addEventListener('keydown', (event) => {
    if (event.target !== canvas && event.target !== pourButton) return;
    const pourKey = event.code === 'Space' || (event.code === 'Enter' && event.target === pourButton);
    if (pourKey) {
      event.preventDefault();
      if (finished || document.hidden) return;
      keys.add(event.code);
      pourButton.setAttribute('aria-pressed', 'true');
      wake();
    } else if (event.target === canvas && (event.code === 'ArrowLeft' || event.code === 'ArrowRight')) {
      event.preventDefault();
      setAngle(angle + (event.code === 'ArrowLeft' ? -3 : 3));
    }
  });
  root.addEventListener('keyup', (event) => {
    if (!keys.has(event.code)) return;
    event.preventDefault();
    keys.delete(event.code);
    pourButton.setAttribute('aria-pressed', String(pouring()));
    draw();
  });
  root.addEventListener('focusout', (event) => {
    if (event.target === pourButton || event.target === canvas) {
      keys.clear();
      pourButton.setAttribute('aria-pressed', String(pouring()));
    }
  });
  canvas.addEventListener('pointerdown', (event) => {
    if (finished || document.hidden || dragPointer !== null || (event.pointerType === 'mouse' && event.button !== 0)) return;
    event.preventDefault();
    canvas.focus({ preventScroll: true });
    dragPointer = event.pointerId;
    dragX = event.clientX;
    dragAngle = angle;
    canvas.setPointerCapture(event.pointerId);
    wake();
  });
  canvas.addEventListener('pointermove', (event) => {
    if (event.pointerId !== dragPointer) return;
    const width = Math.max(1, canvas.getBoundingClientRect().width);
    setAngle(dragAngle + (event.clientX - dragX) * 180 / width);
  });
  function releaseDrag(event) {
    if (event.pointerId === dragPointer) dragPointer = null;
  }
  canvas.addEventListener('pointerup', releaseDrag);
  canvas.addEventListener('pointercancel', releaseDrag);
  canvas.addEventListener('lostpointercapture', releaseDrag);
  tiltInput.addEventListener('input', () => setAngle(tiltInput.value));
  resetButton.addEventListener('click', newRound);
  finishButton.addEventListener('click', () => {
    if (finished) return;
    stopFrame();
    releaseInputs();
    finished = true;
    paused = false;
    const error = Math.abs(volume - target) * 100;
    const score = Math.max(0, Math.round(1000 - error * 22 - spilled * 100 * 12));
    best = best === null ? score : Math.max(best, score);
    bestOutput.textContent = `${best} / 1000`;
    pourButton.disabled = true;
    finishButton.disabled = true;
    tiltInput.disabled = true;
    resetButton.textContent = 'Next round';
    status.textContent = `Score: ${score} / 1000. Target error: ${error.toFixed(1)} percentage points. Spill: ${(spilled * 100).toFixed(1)}%. Scoring: 1000 minus 22 per error point and 12 per spilled point, minimum 0. Start the next round to try again.`;
    draw();
  });
  newRound();
  return { deactivate };
}
