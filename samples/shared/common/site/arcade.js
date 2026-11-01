import { mountSnake, mountMemory } from './traditional.js';
import { mountReaction } from './reaction.js';
import { mountWater } from './water.js';

const games = new Map([
  ['snake', mountSnake(document.querySelector('#game-snake'))],
  ['memory', mountMemory(document.querySelector('#game-memory'))],
  ['reaction', mountReaction(document.querySelector('#game-reaction'))],
  ['water', mountWater(document.querySelector('#game-water'))]
]);
const tabs = Array.from(document.querySelectorAll('[data-game]'));
let current = 'snake';
function showGame(name) {
  if (!games.has(name)) return;
  if (current !== name) games.get(current).deactivate();
  current = name;
  for (const tab of tabs) {
    const selected = tab.dataset.game === name;
    tab.setAttribute('aria-selected', String(selected));
    tab.tabIndex = selected ? 0 : -1;
    document.querySelector(`#game-${tab.dataset.game}`).hidden = !selected;
  }
  history.replaceState(null, '', `#${name}`);
}
for (const tab of tabs) {
  tab.addEventListener('click', () => showGame(tab.dataset.game));
  tab.addEventListener('keydown', event => {
    const index = tabs.indexOf(tab);
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else return;
    event.preventDefault();
    showGame(tabs[next].dataset.game);
    tabs[next].focus();
  });
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) games.get(current).deactivate();
});
window.addEventListener('pagehide', () => games.get(current).deactivate());
showGame(games.has(location.hash.slice(1)) ? location.hash.slice(1) : 'snake');
window.addEventListener('blur', () => games.get(current).deactivate());
