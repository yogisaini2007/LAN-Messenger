/*
 * LAN Messenger - Native Sound Event Controller
 * Uses Java audio instead of WebView Audio/data URLs.
 * Fixed: no emoji rendering, uses SVG icons instead.
 */
(function () {
  'use strict';

  const state = {
    selfId: '',
    seen: new Set(),
    lastNotification: 0
  };

  function java() {
    return window.lanSounds;
  }

  function play(type) {
    try {
      if (java()) java().play(type);
    } catch (error) {
      console.warn('Native sound:', error);
    }
  }

  function remember(key) {
    if (state.seen.has(key)) return false;

    state.seen.add(key);

    if (state.seen.size > 2000) {
      const oldest = state.seen.values().next().value;
      state.seen.delete(oldest);
    }

    return true;
  }

  function notification(key) {
    if (key && !remember('notification:' + key)) {
      return;
    }

    const now = Date.now();

    if (now - state.lastNotification < 1200) {
      return;
    }

    state.lastNotification = now;
    play('notification');
  }

  function message(scope, item) {
    if (!item || !item.id || !state.selfId) return;
    if (item.deleted || item.deletedForEveryone) return;

    const sender = String(item.senderId || '');
    if (!sender) return;

    const mine = sender === state.selfId;
    const key = scope + ':' + item.id;

    if (!remember('message:' + key)) return;

    if (mine && item.timestamp) {
      const age = Date.now() - Number(item.timestamp);
      if (age > 120000) return;
    }

    play(mine ? 'sent' : 'received');
  }

  function attachment(scope, payload) {
    if (!payload || !state.selfId) return;

    const item = payload.item || payload;

    if (!item.id || !item.senderId) return;

    if (String(item.senderId) === state.selfId) {
      return;
    }

    if (item.hidden ||
        item.available === false ||
        item.status === 'FAILED') {
      return;
    }

    notification(scope + ':' + item.id);
  }

  function handle(scope, type, payload) {
    if (
      type === 'signedIn' ||
      type === 'profile' ||
      type === 'ready'
    ) {
      const data = payload || {};
      const id = data.selfId || data.id;

      if (id) {
        const next = String(id);

        if (state.selfId && state.selfId !== next) {
          state.seen.clear();
        }

        state.selfId = next;
      }
    }

    if (type === 'message') {
      message(scope, payload);
    }

    if (type === 'attachmentChanged') {
      attachment(scope, payload);
    }

    if (
      scope === 'PRIVATE' &&
      type === 'notice' &&
      typeof payload === 'string' &&
      /^Received\s+/i.test(payload)
    ) {
      notification();
    }
  }

  function hook(name, scope) {
    const target = window[name];

    if (!target || typeof target.receive !== 'function') {
      return false;
    }

    if (target.receive.__nativeSounds) {
      return true;
    }

    const original = target.receive;

    function wrapped(type, payload) {
      const result = original.apply(this, arguments);

      try {
        handle(scope, type, payload);
      } catch (error) {
        console.warn('Sound event:', error);
      }

      return result;
    }

    wrapped.__nativeSounds = true;
    target.receive = wrapped;

    return true;
  }

  function speakerSvg(enabled) {
    if (enabled) {
      return `
        <svg viewBox="0 0 24 24" class="lan-sound-svg" aria-hidden="true">
          <path d="M5 9v6h4l5 4V5L9 9H5z"></path>
          <path d="M16 9.5a4.5 4.5 0 0 1 0 5"></path>
          <path d="M18.5 7a8 8 0 0 1 0 10"></path>
        </svg>
      `;
    }

    return `
      <svg viewBox="0 0 24 24" class="lan-sound-svg" aria-hidden="true">
        <path d="M5 9v6h4l5 4V5L9 9H5z"></path>
        <path d="M18 9l-6 6"></path>
        <path d="M12 9l6 6"></path>
      </svg>
    `;
  }

  function updateToggle() {
    const button = document.getElementById(
      'lan-native-sound-toggle'
    );

    if (!button || !java()) return;

    try {
      const enabled = Boolean(java().isEnabled());

      button.innerHTML = speakerSvg(enabled);
      button.title = enabled
        ? 'Mute message sounds'
        : 'Enable message sounds';

      button.setAttribute(
        'aria-label',
        button.title
      );
    } catch (error) {
      console.warn('Sound setting:', error);
    }
  }

  function addToggle() {
    if (document.getElementById(
      'lan-native-sound-toggle'
    )) {
      return;
    }

    const heading = document.querySelector(
      '.sidebar .section-title'
    );

    if (!heading) return;

    const button = document.createElement('button');

    button.type = 'button';
    button.id = 'lan-native-sound-toggle';
    button.title = 'Mute message sounds';

    button.onclick = event => {
      event.preventDefault();
      event.stopPropagation();

      if (!java()) return;

      const next = !Boolean(java().isEnabled());

      java().setEnabled(next);
      updateToggle();

      if (next) play('received');
    };

    heading.appendChild(button);
    updateToggle();
  }

  const style = document.createElement('style');

  style.textContent = `
    #lan-native-sound-toggle {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 34px;
      height: 34px;
      min-width: 34px;
      margin-left: auto;
      padding: 0;
      border-radius: 10px;
      border: 1px solid rgba(125,125,125,.20);
      background: rgba(120,120,120,.09);
      cursor: pointer;
      color: inherit;
    }

    #lan-native-sound-toggle:hover {
      background: rgba(120,120,120,.18);
    }

    .lan-sound-svg {
      width: 18px;
      height: 18px;
      stroke: currentColor;
      fill: none;
      stroke-width: 2;
      stroke-linecap: round;
      stroke-linejoin: round;
      display: block;
      pointer-events: none;
    }
  `;

  document.head.appendChild(style);

  addToggle();

  const timer = setInterval(() => {
    addToggle();

    const privateReady = hook('LAN', 'PRIVATE');
    const groupReady = hook('LANGroups', 'GROUP');

    updateToggle();

    if (privateReady && groupReady) {
      clearInterval(timer);
    }
  }, 250);

  window.LANNativeSounds = Object.freeze({
    test: play,
    refresh: updateToggle
  });

})();