export function mountSnake(root) {
  root.innerHTML = `
    <h2>Snake</h2>
    <div class="toolbar">
      <button type="button" data-snake-start>Start</button>
      <button type="button" class="secondary" data-snake-pause disabled>Pause</button>
      <button type="button" class="secondary" data-snake-restart>Restart</button>
    </div>
    <div class="metrics">
      <div class="metric">Score <strong data-snake-score>0</strong></div>
      <div class="metric">Length <strong data-snake-length>3</strong></div>
    </div>
    <p class="status" data-snake-status role="status" aria-live="polite"></p>
    <canvas class="game-canvas" width="400" height="400" tabindex="0"
      aria-label="Snake game board. Focus here and use the arrow keys or W, A, S, D to steer.">
      Snake requires a browser with canvas support.
    </canvas>
    <div class="control-pad" role="group" aria-label="Snake direction controls">
      <button type="button" data-direction="up" style="grid-column: 2; grid-row: 1">Up</button>
      <button type="button" data-direction="left" style="grid-column: 1; grid-row: 2">Left</button>
      <button type="button" data-direction="down" style="grid-column: 2; grid-row: 2">Down</button>
      <button type="button" data-direction="right" style="grid-column: 3; grid-row: 2">Right</button>
    </div>
    <p class="hint">Eat the food dots without hitting a wall or yourself. Focus the board to use arrow keys or W, A, S, D, or use the direction buttons.</p>
  `;

  const canvas = root.querySelector('canvas');
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Snake requires canvas support.');
  const startButton = root.querySelector('[data-snake-start]');
  const pauseButton = root.querySelector('[data-snake-pause]');
  const scoreLabel = root.querySelector('[data-snake-score]');
  const lengthLabel = root.querySelector('[data-snake-length]');
  const status = root.querySelector('[data-snake-status]');
  const size = 20;
  const cell = canvas.width / size;
  const directions = {
    up: { x: 0, y: -1 },
    down: { x: 0, y: 1 },
    left: { x: -1, y: 0 },
    right: { x: 1, y: 0 },
  };
  const keys = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    w: 'up', s: 'down', a: 'left', d: 'right',
  };
  const background = context.createLinearGradient(0, 0, canvas.width, canvas.height);
  background.addColorStop(0, '#fafafa');
  background.addColorStop(1, '#f0f0f0');
  let snake;
  let food;
  let direction;
  let nextDirection;
  let turnQueued;
  let score;
  let state;
  let timer = null;

  function stopTimer() {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  }

  function spawnFood() {
    const free = [];
    const occupied = new Set(snake.map(segment => segment.y * size + segment.x));
    for (let index = 0; index < size * size; index += 1) {
      if (!occupied.has(index)) free.push(index);
    }
    if (free.length === 0) return null;
    const index = free[Math.floor(Math.random() * free.length)];
    return { x: index % size, y: Math.floor(index / size) };
  }

  function draw() {
    context.fillStyle = background;
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.strokeStyle = 'rgba(0, 0, 0, 0.06)';
    context.lineWidth = 1;
    context.beginPath();
    for (let index = 1; index < size; index += 1) {
      const position = index * cell + 0.5;
      context.moveTo(position, 0);
      context.lineTo(position, canvas.height);
      context.moveTo(0, position);
      context.lineTo(canvas.width, position);
    }
    context.stroke();

    if (food) {
      const x = (food.x + 0.5) * cell;
      const y = (food.y + 0.5) * cell;
      context.fillStyle = 'rgba(0, 0, 0, 0.10)';
      context.beginPath();
      context.arc(x, y, cell * 0.47, 0, Math.PI * 2);
      context.fill();
      context.fillStyle = '#707070';
      context.beginPath();
      context.arc(x, y, cell * 0.3, 0, Math.PI * 2);
      context.fill();
    }

    snake.forEach((segment, index) => {
      context.fillStyle = index === 0 ? '#111111' : '#353535';
      context.fillRect(segment.x * cell + 2, segment.y * cell + 2, cell - 4, cell - 4);
      context.fillStyle = 'rgba(255, 255, 255, 0.22)';
      context.fillRect(segment.x * cell + 3, segment.y * cell + 3, cell - 6, 2);
    });
    const head = snake[0];
    const centerX = (head.x + 0.5) * cell;
    const centerY = (head.y + 0.5) * cell;
    context.fillStyle = '#ffffff';
    for (const side of [-1, 1]) {
      context.beginPath();
      context.arc(
        centerX + direction.x * 4 - direction.y * side * 4,
        centerY + direction.y * 4 + direction.x * side * 4,
        1.7, 0, Math.PI * 2,
      );
      context.fill();
    }

    if (state !== 'running') {
      context.fillStyle = 'rgba(255, 255, 255, 0.85)';
      context.fillRect(0, canvas.height / 2 - 36, canvas.width, 72);
      context.textAlign = 'center';
      context.textBaseline = 'middle';
      context.fillStyle = '#111111';
      context.font = '600 24px system-ui, sans-serif';
      const title = { ready: 'Ready to play', paused: 'Paused', over: 'Game over', won: 'Board complete!' };
      context.fillText(title[state], canvas.width / 2, canvas.height / 2);
    }
  }

  function updateControls() {
    scoreLabel.textContent = String(score);
    lengthLabel.textContent = String(snake.length);
    startButton.disabled = state === 'running';
    startButton.textContent = state === 'paused' ? 'Resume' : (state === 'over' || state === 'won' ? 'Play again' : 'Start');
    pauseButton.disabled = state !== 'running';
  }

  function reset() {
    stopTimer();
    snake = [{ x: 7, y: 10 }, { x: 6, y: 10 }, { x: 5, y: 10 }];
    direction = directions.right;
    nextDirection = direction;
    turnQueued = false;
    score = 0;
    state = 'ready';
    food = spawnFood();
    status.textContent = 'Ready. Select Start, then steer toward the food.';
    updateControls();
    draw();
  }

  function finish(won) {
    stopTimer();
    state = won ? 'won' : 'over';
    nextDirection = direction;
    turnQueued = false;
    status.textContent = won
      ? `You filled the board! Final score: ${score}. Select Play again for a new round.`
      : `Game over. Final score: ${score}. Select Play again for a new round.`;
    updateControls();
    draw();
  }

  function tick() {
    direction = nextDirection;
    turnQueued = false;
    const head = { x: snake[0].x + direction.x, y: snake[0].y + direction.y };
    const growing = food !== null && head.x === food.x && head.y === food.y;
    const occupiedLength = snake.length - (growing ? 0 : 1);
    const hitBody = snake.some((segment, index) => index < occupiedLength && segment.x === head.x && segment.y === head.y);
    if (head.x < 0 || head.x >= size || head.y < 0 || head.y >= size || hitBody) {
      finish(false);
      return;
    }
    snake.unshift(head);
    if (growing) {
      score += 10;
      food = spawnFood();
      updateControls();
      if (food === null) {
        finish(true);
        return;
      }
    } else {
      snake.pop();
    }
    draw();
  }

  function start() {
    if (state === 'running') return;
    if (state === 'over' || state === 'won') reset();
    state = 'running';
    status.textContent = 'Playing. Use the focused board or the direction buttons to steer.';
    updateControls();
    draw();
    timer = setInterval(tick, 130);
    canvas.focus({ preventScroll: true });
  }

  function pause() {
    if (state !== 'running') return;
    stopTimer();
    state = 'paused';
    nextDirection = direction;
    turnQueued = false;
    status.textContent = 'Paused. Select Resume to continue.';
    updateControls();
    draw();
  }

  function steer(name) {
    if (state !== 'running' || turnQueued) return;
    const requested = directions[name];
    if (!requested || requested === direction) return;
    if (requested.x === -direction.x && requested.y === -direction.y) return;
    nextDirection = requested;
    turnQueued = true;
  }

  canvas.addEventListener('keydown', event => {
    const name = keys[event.key] || keys[event.key.toLowerCase()];
    if (!name || event.altKey || event.ctrlKey || event.metaKey) return;
    event.preventDefault();
    steer(name);
  });
  root.querySelectorAll('[data-direction]').forEach(button => {
    button.addEventListener('click', () => {
      steer(button.dataset.direction);
      canvas.focus({ preventScroll: true });
    });
  });
  startButton.addEventListener('click', start);
  pauseButton.addEventListener('click', pause);
  root.querySelector('[data-snake-restart]').addEventListener('click', () => {
    reset();
    start();
  });
  reset();
  return { deactivate: pause };
}

export function mountMemory(root) {
  root.innerHTML = `
    <h2>Memory pairs</h2>
    <div class="toolbar">
      <button type="button" data-memory-restart>Restart</button>
    </div>
    <div class="metrics">
      <div class="metric">Moves <strong data-memory-moves>0</strong></div>
      <div class="metric">Pairs <strong data-memory-pairs>0 / 8</strong></div>
    </div>
    <p class="status" data-memory-status role="status" aria-live="polite"></p>
    <div class="memory-grid" role="group" aria-label="Sixteen memory cards"></div>
    <p class="hint">Find the eight matching letter pairs. Select cards with a tap or click, or use Tab and Enter or Space.</p>
  `;

  const grid = root.querySelector('.memory-grid');
  const movesLabel = root.querySelector('[data-memory-moves]');
  const pairsLabel = root.querySelector('[data-memory-pairs]');
  const status = root.querySelector('[data-memory-status]');
  const buttons = [];
  let deck = [];
  let selected = [];
  let matched = new Set();
  let moves = 0;
  let pairs = 0;
  let locked = false;
  let timeout = null;
  let generation = 0;

  function clearPending() {
    generation += 1;
    if (timeout !== null) {
      clearTimeout(timeout);
      timeout = null;
    }
  }

  function paintCard(index) {
    const button = buttons[index];
    const isMatched = matched.has(index);
    const isRevealed = isMatched || selected.includes(index);
    button.textContent = isRevealed ? deck[index] : '?';
    button.classList.toggle('revealed', isRevealed);
    button.classList.toggle('matched', isMatched);
    button.disabled = isMatched;
    button.setAttribute('aria-label', isRevealed
      ? `Card ${index + 1}, ${deck[index]}, ${isMatched ? 'matched' : 'revealed'}`
      : `Card ${index + 1}, face down`);
  }

  function updateCounters() {
    movesLabel.textContent = String(moves);
    pairsLabel.textContent = `${pairs} / 8`;
  }

  function flip(index) {
    if (locked || matched.has(index) || selected.includes(index)) return;
    selected.push(index);
    paintCard(index);
    if (selected.length === 1) {
      status.textContent = `Card ${index + 1} shows ${deck[index]}. Choose another card.`;
      return;
    }

    moves += 1;
    const [first, second] = selected;
    if (deck[first] === deck[second]) {
      matched.add(first);
      matched.add(second);
      selected = [];
      pairs += 1;
      paintCard(first);
      paintCard(second);
      updateCounters();
      status.textContent = pairs === 8
        ? `All eight pairs found in ${moves} moves! Select Restart to play again.`
        : `Matched ${deck[first]}. ${pairs} of eight pairs found. Choose another card.`;
      return;
    }

    locked = true;
    updateCounters();
    status.textContent = `${deck[first]} and ${deck[second]} do not match. Watch them, then try again.`;
    const round = generation;
    timeout = setTimeout(() => {
      if (round !== generation) return;
      timeout = null;
      selected = [];
      locked = false;
      paintCard(first);
      paintCard(second);
      status.textContent = 'Cards hidden. Choose two cards to find a pair.';
    }, 1000);
  }

  function reset() {
    clearPending();
    deck = [...'ABCDEFGH', ...'ABCDEFGH'];
    for (let index = deck.length - 1; index > 0; index -= 1) {
      const other = Math.floor(Math.random() * (index + 1));
      [deck[index], deck[other]] = [deck[other], deck[index]];
    }
    selected = [];
    matched = new Set();
    moves = 0;
    pairs = 0;
    locked = false;
    buttons.forEach((button, index) => paintCard(index));
    updateCounters();
    status.textContent = 'Find all eight letter pairs. Choose a card to begin.';
  }

  for (let index = 0; index < 16; index += 1) {
    const button = root.ownerDocument.createElement('button');
    button.type = 'button';
    button.className = 'memory-card';
    button.addEventListener('click', () => flip(index));
    buttons.push(button);
    grid.append(button);
  }
  root.querySelector('[data-memory-restart]').addEventListener('click', reset);
  reset();

  return {
    deactivate() {
      clearPending();
      const revealed = selected;
      selected = [];
      locked = false;
      revealed.forEach(paintCard);
      if (revealed.length > 0) status.textContent = 'Unmatched cards hidden. Choose a card to continue.';
    },
  };
}
