
/*
 * LAN Messenger - Notification Sound Module
 *
 * Independent module:
 * - Sent message tone
 * - Received message tone
 * - Incoming attachment / notification tone
 * - Mute control
 * - Prevents duplicate sounds
 * - No external audio files required
 */
(function () {
  'use strict';

  const STORAGE_KEY = 'lan-messenger-sounds-enabled';

  const patterns = {
    sent: [
      { f: 784, start: 0, duration: 0.065 },
      { f: 1047, start: 0.065, duration: 0.075 }
    ],

    received: [
      { f: 587, start: 0, duration: 0.095 },
      { f: 880, start: 0.105, duration: 0.12 }
    ],

    notification: [
      { f: 523, start: 0, duration: 0.08 },
      { f: 659, start: 0.09, duration: 0.08 },
      { f: 880, start: 0.18, duration: 0.14 }
    ]
  };

  let enabled = true;
  let selfId = '';
  let audioContext = null;
  let soundButton = null;

  const soundCache = {};
  const played = new Set();
  const attachmentSnapshots = new Map();

  try {
    enabled = localStorage.getItem(STORAGE_KEY) !== 'false';
  } catch (error) {
    enabled = true;
  }

  function remember(key) {
    if (played.has(key)) return false;

    played.add(key);

    if (played.size > 2500) {
      const oldest = played.values().next().value;
      played.delete(oldest);
    }

    return true;
  }

  function currentProfile(data) {
    if (!data || typeof data !== 'object') return;

    const profile =
      data.profile ||
      data.account ||
      data.user ||
      data;

    const id =
      profile.selfId ||
      profile.id ||
      profile.accountId ||
      '';

    if (id) selfId = String(id);
  }

  function makeWav(type) {
    if (soundCache[type]) return soundCache[type];

    const notes = patterns[type];
    if (!notes) return null;

    const rate = 22050;

    const length = Math.max.apply(
      null,
      notes.map(n => n.start + n.duration)
    ) + 0.035;

    const count = Math.ceil(length * rate);

    const buffer = new ArrayBuffer(44 + count * 2);
    const view = new DataView(buffer);
    const bytes = new Uint8Array(buffer);

    function writeText(offset, value) {
      for (let i = 0; i < value.length; i++) {
        view.setUint8(
          offset + i,
          value.charCodeAt(i)
        );
      }
    }

    writeText(0, 'RIFF');
    view.setUint32(4, 36 + count * 2, true);
    writeText(8, 'WAVE');
    writeText(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, rate, true);
    view.setUint32(28, rate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    writeText(36, 'data');
    view.setUint32(40, count * 2, true);

    for (let i = 0; i < count; i++) {
      const time = i / rate;
      let sample = 0;

      for (const note of notes) {
        const relative = time - note.start;

        if (
          relative < 0 ||
          relative > note.duration
        ) {
          continue;
        }

        const attack = Math.min(
          1,
          relative / 0.012
        );

        const release = Math.min(
          1,
          (note.duration - relative) / 0.035
        );

        const envelope = Math.max(
          0,
          Math.min(attack, release)
        );

        sample += Math.sin(
          2 * Math.PI * note.f * relative
        ) * envelope * 0.18;
      }

      sample = Math.max(-1, Math.min(1, sample));

      view.setInt16(
        44 + i * 2,
        Math.round(sample * 32767),
        true
      );
    }

    let binary = '';

    for (let i = 0; i < bytes.length; i += 8192) {
      const chunk = bytes.subarray(i, i + 8192);
      binary += String.fromCharCode.apply(null, chunk);
    }

    soundCache[type] =
      'data:audio/wav;base64,' + btoa(binary);

    return soundCache[type];
  }

  function playWebAudio(type) {
    const AudioClass =
      window.AudioContext ||
      window.webkitAudioContext;

    if (!AudioClass) return false;

    try {
      if (!audioContext) {
        audioContext = new AudioClass();
      }

      if (audioContext.state === 'suspended') {
        audioContext.resume().catch(() => {});
      }

      const start = audioContext.currentTime + 0.01;

      for (const note of patterns[type]) {
        const oscillator = audioContext.createOscillator();
        const gain = audioContext.createGain();

        const begin = start + note.start;
        const end = begin + note.duration;

        oscillator.type = 'sine';
        oscillator.frequency.setValueAtTime(
          note.f,
          begin
        );

        gain.gain.setValueAtTime(0.0001, begin);
        gain.gain.linearRampToValueAtTime(
          0.13,
          begin + 0.012
        );
        gain.gain.exponentialRampToValueAtTime(
          0.0001,
          end
        );

        oscillator.connect(gain);
        gain.connect(audioContext.destination);

        oscillator.start(begin);
        oscillator.stop(end + 0.01);
      }

      return audioContext.state === 'running';

    } catch (error) {
      return false;
    }
  }

  function playWav(type) {
    try {
      const source = makeWav(type);
      if (!source) return;

      const audio = new Audio(source);
      audio.volume = 0.65;

      const result = audio.play();

      if (
        result &&
        typeof result.catch === 'function'
      ) {
        result.catch(error => {
          console.warn(
            'LAN sound unavailable:',
            error.message
          );
        });
      }

    } catch (error) {
      console.warn(
        'LAN sound playback failed:',
        error
      );
    }
  }

  function play(type, force) {
    if (!enabled && !force) return;
    if (!patterns[type]) return;

    if (!playWebAudio(type)) {
      playWav(type);
    }
  }

  function notifyMessage(message, scope) {
    if (!message || !message.id || !selfId) return;

    const sender = String(message.senderId || '');

    if (!sender) return;

    const mine = sender === selfId;

    // Do not replay tones for message receipts or read updates.
    if (
      mine &&
      scope === 'private' &&
      message.status &&
      message.status !== 'PENDING'
    ) {
      return;
    }

    const key =
      'message:' + scope + ':' + message.id;

    if (!remember(key)) return;

    play(mine ? 'sent' : 'received');
  }

  function notifyAttachment(item, scope) {
    if (
      !item ||
      !item.id ||
      !selfId ||
      item.hidden
    ) {
      return;
    }

    const mine =
      String(item.senderId || '') === selfId;

    if (mine) return;

    const key =
      'attachment:' + scope + ':' + item.id;

    if (!remember(key)) return;

    play('notification');
  }

  function attachmentHistory(payload, scope) {
    if (
      !payload ||
      !Array.isArray(payload.items)
    ) {
      return;
    }

    const chatId =
      payload.groupId ||
      payload.peerId ||
      payload.chatId;

    if (!chatId) return;

    const key = scope + ':' + chatId;
    const ids = new Set(
      payload.items.map(item => item.id)
    );

    const previous = attachmentSnapshots.get(key);

    // Initial history is just a baseline.
    // We must not play a sound for every old file.
    if (previous) {
      for (const item of payload.items) {
        if (!previous.has(item.id)) {
          notifyAttachment(item, scope);
        }
      }
    }

    attachmentSnapshots.set(key, ids);
  }

  function handleEvent(scope, type, payload) {
    try {
      if (
        type === 'ready' ||
        type === 'signedIn' ||
        type === 'profile' ||
        type === 'account'
      ) {
        currentProfile(payload);
      }

      if (type === 'message') {
        notifyMessage(payload, scope);
      }

      if (type === 'attachmentChanged') {
        if (
          payload &&
          payload.available &&
          scope === 'private'
        ) {
          notifyAttachment(payload, scope);
        }
      }

      if (type === 'attachmentHistory') {
        attachmentHistory(payload, scope);
      }

      if (type === 'notification') {
        play('notification');
      }

    } catch (error) {
      console.warn('LAN sound event error:', error);
    }
  }

  function attachReceiver(name, scope) {
    const receiver = window[name];

    if (
      !receiver ||
      typeof receiver.receive !== 'function'
    ) {
      return false;
    }

    if (receiver.receive.__lanSoundWrapped) {
      return true;
    }

    const original = receiver.receive;

    function wrapped(type, payload) {
      const result = original.apply(this, arguments);

      handleEvent(scope, type, payload);

      return result;
    }

    wrapped.__lanSoundWrapped = true;
    receiver.receive = wrapped;

    return true;
  }

  function installHooks() {
    let tries = 0;

    const timer = setInterval(() => {
      const privateReady = attachReceiver(
        'LAN',
        'private'
      );

      const groupsReady = attachReceiver(
        'LANGroups',
        'groups'
      );

      tries++;

      if (
        (privateReady && groupsReady) ||
        tries >= 300
      ) {
        clearInterval(timer);
      }
    }, 100);

    attachReceiver('LAN', 'private');
    attachReceiver('LANGroups', 'groups');
  }

  function updateButton() {
    if (!soundButton) return;

    soundButton.textContent =
      enabled ? '🔊' : '🔇';

    soundButton.title = enabled
      ? 'Sounds ON - Click to mute'
      : 'Sounds OFF - Click to enable';

    soundButton.setAttribute(
      'aria-label',
      enabled ? 'Mute sounds' : 'Enable sounds'
    );
  }

  function setEnabled(value) {
    enabled = Boolean(value);

    try {
      localStorage.setItem(
        STORAGE_KEY,
        String(enabled)
      );
    } catch (error) {
      // Keep the in-memory setting when storage is unavailable.
    }

    updateButton();
  }

  function createButton() {
    const heading = document.querySelector(
      '.sidebar .section-title'
    );

    if (!heading) return;

    soundButton = document.createElement('button');
    soundButton.type = 'button';
    soundButton.className = 'lan-sound-toggle';

    soundButton.onclick = event => {
      event.stopPropagation();

      setEnabled(!enabled);

      if (enabled) {
        play('received', true);
      }
    };

    heading.appendChild(soundButton);

    updateButton();
  }

  const style = document.createElement('style');

  style.id = 'lan-notification-sounds-style';

  style.textContent = `
    .lan-sound-toggle {
      margin-left: auto;
      width: 31px;
      height: 31px;
      min-width: 31px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0;
      border-radius: 9px;
      border: 1px solid rgba(140,140,140,.25);
      background: rgba(125,125,125,.1);
      cursor: pointer;
      font-size: 16px;
      vertical-align: middle;
    }

    .lan-sound-toggle:hover {
      background: rgba(125,125,125,.2);
    }
  `;

  document.head.appendChild(style);

  createButton();
  installHooks();

  // Useful for testing from JavaScript console.
  window.LANSounds = Object.freeze({
    test: function (type) {
      play(type || 'received', true);
    },

    enable: setEnabled,

    isEnabled: function () {
      return enabled;
    }
  });

})();
