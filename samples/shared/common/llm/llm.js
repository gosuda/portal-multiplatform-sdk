// Authentication stays in page memory; links never contain credentials.
const passwordForm = document.querySelector('#password-form');
const passwordInput = document.querySelector('#password');
const passwordDetails = document.querySelector('#password-details');
const connectButton = document.querySelector('#connect-button');
const connectionStatus = document.querySelector('#connection-status');
const modelName = document.querySelector('#model-name');
const execution = document.querySelector('#execution');
const outputLimit = document.querySelector('#output-limit');
const contextLimit = document.querySelector('#context-limit');
const promptForm = document.querySelector('#prompt-form');
const promptInput = document.querySelector('#prompt');
const maxTokensInput = document.querySelector('#max-tokens');
const generateButton = document.querySelector('#generate-button');
const generateLabel = document.querySelector('#generate-label');
const errorPanel = document.querySelector('#error-panel');
const errorMessage = document.querySelector('#error-message');
const answerCard = document.querySelector('#answer-card');
const answerState = document.querySelector('#answer-state');
const answerPlaceholder = document.querySelector('#answer-placeholder');
const answer = document.querySelector('#answer');
const answerMeta = document.querySelector('#answer-meta');
const encoder = new TextEncoder();
let authorization = '';
let authenticated = false;
let connecting = false;
let generating = false;
let maxOutputTokens = 128;

class HttpError extends Error {
  constructor(status) {
    super('The host rejected the request.');
    this.status = status;
  }
}

class ResponseError extends Error {}

function updateControls() {
  const busy = connecting || generating;
  passwordInput.disabled = busy;
  connectButton.disabled = busy;
  promptInput.disabled = !authenticated || busy;
  maxTokensInput.disabled = !authenticated || busy;
  generateButton.disabled = !authenticated || busy;
  generateLabel.textContent = generating ? 'Generating on host…' : 'Generate answer';
  answerCard.setAttribute('aria-busy', String(generating));
}

function clearError() {
  errorPanel.hidden = true;
  errorMessage.textContent = '';
}

function showError(message) {
  errorMessage.textContent = message;
  errorPanel.hidden = false;
}

function clearHost(message) {
  authenticated = false;
  connectionStatus.textContent = message;
  connectionStatus.dataset.connected = 'false';
  modelName.textContent = 'Not connected';
  execution.textContent = 'Not verified';
  outputLimit.textContent = '—';
  contextLimit.textContent = '—';
}

function errorDescription(error) {
  if (error instanceof HttpError) {
    switch (error.status) {
      case 400: return 'The host could not accept this prompt or token limit. Shorten the prompt to fit the model’s context window, check the output limit, and try again.';
      case 401: return 'The password was rejected. Enter the current password from the host app’s Local LLM settings under “Log in with a password.”';
      case 409: return 'The host is already generating an answer for another request. Only one request can run at a time. Wait for it to finish, then try again.';
      case 413: return 'This request is too large. Shorten your prompt and try again; the request body must fit within 16 KiB.';
      case 500:
      case 503: return 'The device-local model could not complete this request. Check the host app for details and keep it in the foreground before trying again.';
      default: return `The host returned HTTP ${error.status}. Check that this is the current local-AI session link from the host app.`;
    }
  }
  if (error instanceof ResponseError) return error.message;
  return 'Could not reach the host. Keep the host app running in the foreground and check the Portal connection. If the session restarted, open its new public link and enter the configured password.';
}

function handleError(error) {
  if (error instanceof HttpError && error.status === 401) {
    authorization = '';
    passwordInput.value = '';
    clearHost('Password rejected');
    passwordDetails.open = true;
  }
  showError(errorDescription(error));
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...options.headers, Authorization: authorization },
    credentials: 'omit',
    mode: 'same-origin',
    cache: 'no-store',
    redirect: 'error',
    referrerPolicy: 'no-referrer',
  });
  if (!response.ok) throw new HttpError(response.status);
  try {
    return await response.json();
  } catch {
    throw new ResponseError('The host returned an unreadable response. Check the host app and try again.');
  }
}

async function connect(password) {
  if (connecting || generating) return;
  const bytes = encoder.encode(`portal:${password}`);
  authorization = `Basic ${btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''))}`;
  passwordInput.value = '';
  connecting = true;
  clearError();
  clearHost('Checking host…');
  updateControls();
  try {
    const status = await request('/api/status');
    if (!status || typeof status.model !== 'string' || !status.model.trim()
      || status.local !== true
      || !Number.isInteger(status.maxTokens) || status.maxTokens < 1 || status.maxTokens > 128
      || !Number.isInteger(status.contextTokens) || status.contextTokens < 1) {
      throw new ResponseError('The host did not report a valid device-local model and its limits. Reopen the local-AI session from the host app.');
    }
    authenticated = true;
    maxOutputTokens = status.maxTokens;
    maxTokensInput.max = String(maxOutputTokens);
    const selectedTokens = Number(maxTokensInput.value);
    maxTokensInput.value = String(Number.isInteger(selectedTokens) && selectedTokens >= 1
      ? Math.min(selectedTokens, maxOutputTokens) : Math.min(96, maxOutputTokens));
    modelName.textContent = status.model;
    execution.textContent = 'Host device · CPU · no cloud';
    outputLimit.textContent = `${status.maxTokens} tokens per answer`;
    contextLimit.textContent = `${status.contextTokens.toLocaleString()} tokens, including prompt and answer`;
    connectionStatus.textContent = 'Connected to local model';
    connectionStatus.dataset.connected = 'true';
    passwordDetails.open = false;
  } catch (error) {
    clearHost('Host not connected');
    passwordDetails.open = true;
    handleError(error);
  } finally {
    connecting = false;
    updateControls();
  }
}

passwordForm.addEventListener('submit', event => {
  event.preventDefault();
  const password = passwordInput.value;
  if (!password) {
    showError('Enter the password configured in the host app’s Local LLM settings.');
    passwordInput.focus();
    return;
  }
  void connect(password);
});

promptForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!authenticated || connecting || generating) return;
  clearError();
  const prompt = promptInput.value.trim();
  const maxTokens = Number(maxTokensInput.value);
  if (!prompt) {
    showError('Write a prompt before generating an answer.');
    promptInput.focus();
    return;
  }
  if (!Number.isInteger(maxTokens) || maxTokens < 1 || maxTokens > maxOutputTokens) {
    showError(`Choose a whole-number output limit from 1 to ${maxOutputTokens} tokens.`);
    maxTokensInput.focus();
    return;
  }
  const body = JSON.stringify({ prompt, maxTokens });
  if (encoder.encode(body).byteLength > 16 * 1024) {
    showError('This request is too large. Shorten your prompt so the request body fits within 16 KiB.');
    promptInput.focus();
    return;
  }
  generating = true;
  answer.textContent = '';
  answer.hidden = true;
  answerMeta.textContent = '';
  answerMeta.hidden = true;
  answerPlaceholder.textContent = 'Generating on the host device’s CPU. The answer will appear when the request completes; CPU inference can take time.';
  answerPlaceholder.hidden = false;
  answerState.textContent = 'Generating on device…';
  updateControls();
  try {
    const result = await request('/api/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    if (!result || typeof result.text !== 'string'
      || typeof result.model !== 'string' || !result.model.trim()
      || typeof result.elapsedMs !== 'number' || !Number.isFinite(result.elapsedMs) || result.elapsedMs < 0) {
      throw new ResponseError('The host returned an invalid generation response. Check the host app before trying again.');
    }
    answer.textContent = result.text;
    answer.hidden = result.text.length === 0;
    answerPlaceholder.textContent = 'The model returned an empty answer. Try a different or shorter prompt.';
    answerPlaceholder.hidden = result.text.length !== 0;
    answerMeta.textContent = `${result.model} · ${(result.elapsedMs / 1000).toLocaleString(undefined, { maximumFractionDigits: 2 })} s on host · Independent prompt`;
    answerMeta.hidden = false;
    answerState.textContent = result.text.length ? 'Generation complete' : 'Empty model response';
  } catch (error) {
    answerState.textContent = 'Request not completed';
    answerPlaceholder.textContent = 'No answer was returned. Review the message above before trying again.';
    handleError(error);
  } finally {
    generating = false;
    updateControls();
  }
});

promptInput.addEventListener('keydown', event => {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
    event.preventDefault();
    if (!generateButton.disabled) promptForm.requestSubmit();
  }
});

updateControls();
