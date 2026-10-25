
/*
 * LAN Messenger - Voice Messages
 * Fixed: microphone remains clickable after selecting a chat.
 * Native recording handled by window.lanVoice.
 */
(function () {
  'use strict';

  const $ = id => document.getElementById(id);
  const api = () => window.lanVoice;

  const state = {
    contacts: [],
    groups: [],
    recording: false,
    sending: false,
    startedAt: 0,
    target: null,
    playingId: null,
    attachments: new Map()
  };

  const controls = new Map();
  let lastContactRequest = 0;

  const micIcon = `
    <svg viewBox="0 0 24 24" width="19" height="19"
         fill="none" stroke="currentColor" stroke-width="2"
         stroke-linecap="round" stroke-linejoin="round"
         aria-hidden="true">
      <rect x="9" y="2" width="6" height="12" rx="3"/>
      <path d="M5 10a7 7 0 0 0 14 0M12 17v5M8 22h8"/>
    </svg>`;

  function toast(message) {
    const node = $('toast');
    if (!node) {
      console.log('[Voice]', message);
      return;
    }

    const text = String(message);
    node.textContent = text;
    node.style.display = 'block';

    setTimeout(() => {
      if (node.textContent === text) {
        node.style.display = 'none';
      }
    }, 3500);
  }

  function groupMode() {
    const view = document.querySelector('.group-view');

    return !!view &&
      !view.classList.contains('hidden');
  }

  function syncRows() {
    const search = $('search');
    const query = search
      ? search.value.toLocaleLowerCase().trim()
      : '';

    const visible = state.contacts.filter(contact =>
      String(contact.username || '')
        .toLocaleLowerCase()
        .includes(query)
    );

    const privateRows = document.querySelectorAll(
      '#contacts .contact'
    );

    privateRows.forEach((row, index) => {
      const person = visible[index];

      if (person && person.id) {
        row.dataset.lanPeerId = String(person.id);
      }
    });

    const groupRows = document.querySelectorAll(
      '#group-list .group-contact'
    );

    groupRows.forEach((row, index) => {
      const group = state.groups[index];

      if (group && group.id) {
        row.dataset.lanGroupId = String(group.id);
      }
    });
  }

  function getContext() {
    syncRows();

    if (groupMode()) {
      const row = document.querySelector(
        '#group-list .group-contact.active'
      );

      if (!row) return null;

      return {
        type: 'GROUP',
        id: row.dataset.lanGroupId || '',
        name: $('group-name')
          ? $('group-name').textContent.trim()
          : 'Group'
      };
    }

    const row = document.querySelector(
      '#contacts .contact.active'
    );

    if (!row) return null;

    let id = row.dataset.lanPeerId || '';

    // Fallback when contacts were loaded before
    // this module registered its event listener.
    if (!id) {
      const name = row.querySelector('.contact-name');
      const text = name ? name.textContent.trim() : '';

      const matches = state.contacts.filter(
        contact => contact.username === text
      );

      if (matches.length === 1) {
        id = matches[0].id;
      }
    }

    return {
      type: 'PRIVATE',
      id,
      name: $('peer-name')
        ? $('peer-name').textContent.trim()
        : 'Contact'
    };
  }

  function elapsed() {
    const seconds = Math.min(
      120,
      Math.floor(
        (Date.now() - state.startedAt) / 1000
      )
    );

    return String(Math.floor(seconds / 60))
      .padStart(2, '0') + ':' +
      String(seconds % 60).padStart(2, '0');
  }

  function refreshButtons() {
    const context = getContext();

    for (const [type, control] of controls) {
      const mic = control.mic;
      const cancel = control.cancel;

      const selected = context &&
        context.type === type;

      const owningRecording = state.target &&
        state.target.type === type;

      // FIX: recalculate every time the selected
      // conversation or mode changes.
      // Do not disable solely because contacts
      // have not been cached yet.
      mic.disabled = !!state.sending ||
        (state.recording
          ? !owningRecording
          : !selected);

      let phase = 'idle';

      if (state.sending) {
        phase = 'sending';
      } else if (state.recording && owningRecording) {
        phase = 'recording';
      }

      if (mic.dataset.phase !== phase) {
        mic.dataset.phase = phase;

        if (phase === 'recording') {
          mic.textContent = '■';
          mic.title = 'Stop and send recording';

        } else if (phase === 'sending') {
          mic.textContent = '…';
          mic.title = 'Sending voice message';

        } else {
          mic.innerHTML = micIcon;
          mic.title = 'Record voice message';
        }
      }

      mic.classList.toggle(
        'lan-voice-recording',
        phase === 'recording'
      );

      cancel.hidden = !(
        state.recording && owningRecording
      );
    }

    const status = $('lan-voice-status');

    if (status) {
      status.hidden = !state.recording &&
        !state.sending;

      const text = status.querySelector(
        '.lan-voice-status-text'
      );

      if (text) {
        text.textContent = state.sending
          ? 'Sending voice message...'
          : '● Recording ' + elapsed();
      }
    }
  }

  function reset() {
    state.recording = false;
    state.sending = false;
    state.startedAt = 0;
    state.target = null;

    refreshButtons();
  }

  function requestContacts() {
    if (!window.lanJava ||
        typeof window.lanJava.loadContacts !== 'function') {
      return;
    }

    if (Date.now() - lastContactRequest < 3000) {
      return;
    }

    lastContactRequest = Date.now();

    try {
      window.lanJava.loadContacts();
    } catch (error) {
      console.warn('Voice contact refresh:', error);
    }
  }

  function start() {
    if (!api()) {
      toast('Voice service is not connected');
      return;
    }

    if (state.recording || state.sending) return;

    const context = getContext();

    if (!context) {
      toast('Select a conversation first');
      return;
    }

    if (!context.id) {
      requestContacts();
      toast('Loading conversation details. Try again.');
      return;
    }

    state.target = context;
    state.recording = true;
    state.startedAt = Date.now();

    refreshButtons();

    try {
      api().beginRecording(
        context.type,
        context.id
      );

    } catch (error) {
      reset();
      toast('Could not start the microphone');
      console.error(error);
    }
  }

  function finish() {
    if (!state.recording || state.sending) {
      return;
    }

    if (!api()) {
      reset();
      toast('Voice service disconnected');
      return;
    }

    state.recording = false;
    state.sending = true;

    refreshButtons();

    try {
      api().finishAndSend();

    } catch (error) {
      reset();
      toast('Voice sending failed');
      console.error(error);
    }
  }

  function cancel() {
    if (!state.recording) return;

    try {
      if (api()) api().cancelRecording();

    } catch (error) {
      console.error(error);
    }

    reset();
  }

  function installButton(type, composer, anchor) {
    if (!composer || controls.has(type)) {
      return;
    }

    let mic = composer.querySelector(
      '.lan-voice-mic'
    );

    let cancelButton = composer.querySelector(
      '.lan-voice-cancel'
    );

    if (!mic) {
      mic = document.createElement('button');
      mic.type = 'button';
      mic.className = 'lan-voice-mic';
      mic.setAttribute(
        'aria-label',
        'Record voice message'
      );
    }

    if (!cancelButton) {
      cancelButton = document.createElement('button');
      cancelButton.type = 'button';
      cancelButton.className = 'lan-voice-cancel';
      cancelButton.textContent = '×';
      cancelButton.title = 'Discard recording';
    }

    mic.onclick = event => {
      event.preventDefault();
      event.stopPropagation();

      if (state.sending) return;

      if (state.recording) {
        finish();
      } else {
        start();
      }
    };

    cancelButton.onclick = event => {
      event.preventDefault();
      cancel();
    };

    cancelButton.hidden = true;

    if (!mic.parentElement) {
      if (anchor) {
        anchor.after(mic, cancelButton);
      } else {
        composer.prepend(mic, cancelButton);
      }
    }

    controls.set(type, {
      mic,
      cancel: cancelButton
    });

    refreshButtons();
  }

  function installControls() {
    installButton(
      'PRIVATE',
      $('composer'),
      $('attach')
    );

    installButton(
      'GROUP',
      $('group-composer'),
      $('group-attach')
    );

    if (!$('lan-voice-status')) {
      const status = document.createElement('div');
      status.id = 'lan-voice-status';
      status.className = 'lan-voice-status';
      status.hidden = true;

      const label = document.createElement('span');
      label.className = 'lan-voice-status-text';

      const stop = document.createElement('button');
      stop.type = 'button';
      stop.textContent = 'Stop & Send';
      stop.onclick = finish;

      const discard = document.createElement('button');
      discard.type = 'button';
      discard.textContent = 'Discard';
      discard.onclick = cancel;

      status.append(label, stop, discard);
      document.body.appendChild(status);
    }
  }

  function attachmentKey(type, id) {
    return type + ':' + id;
  }

  function rememberFiles(type, chatId, items) {
    if (!chatId || !Array.isArray(items)) return;

    state.attachments.set(
      attachmentKey(type, chatId),
      items
    );

    setTimeout(renderPlayButtons, 0);
  }

  function updateFile(type, chatId, item) {
    if (!chatId || !item || !item.id) return;

    const key = attachmentKey(type, chatId);
    const items = state.attachments.get(key) || [];

    const index = items.findIndex(
      old => old.id === item.id
    );

    if (index >= 0) {
      items[index] = item;
    } else {
      items.push(item);
    }

    state.attachments.set(key, items);
    setTimeout(renderPlayButtons, 0);
  }

  function isVoiceFile(item) {
    return item &&
      /^voice-.*\.wav$/i.test(
        String(item.filename || '')
      );
  }

  function play(context, item, button) {
    if (!api()) {
      toast('Voice player unavailable');
      return;
    }

    try {
      if (state.playingId === item.id) {
        api().stopPlayback();
        state.playingId = null;
        button.textContent = '▶ Play';
        return;
      }

      state.playingId = item.id;
      button.textContent = '■ Stop';

      api().playAttachment(
        context.type,
        context.id,
        item.id
      );

    } catch (error) {
      state.playingId = null;
      button.textContent = '▶ Play';
      toast('Could not play voice message');
    }
  }

  function renderPlayButtons() {
    const context = getContext();

    if (!context || !context.id) return;

    const root = context.type === 'GROUP'
      ? $('group-messages')
      : $('messages');

    if (!root) return;

    const items = state.attachments.get(
      attachmentKey(context.type, context.id)
    ) || [];

    const cards = Array.from(
      root.querySelectorAll(
        '.attachment-card, .file-card, ' +
        '.file-bubble, .group-file-bubble, ' +
        '.attachment-bubble, .bubble, .group-bubble'
      )
    );

    for (const item of items.filter(isVoiceFile)) {
      if (item.available === false) continue;

      const card = cards.find(node =>
        node.textContent.includes(
          String(item.filename)
        )
      );

      if (!card) continue;

      const duplicate = Array.from(
        card.querySelectorAll('.lan-voice-play')
      ).some(button =>
        button.dataset.voiceId === item.id
      );

      if (duplicate) continue;

      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'lan-voice-play';
      button.dataset.voiceId = item.id;
      button.textContent = '▶ Play';

      button.onclick = event => {
        event.preventDefault();
        event.stopPropagation();
        play(context, item, button);
      };

      card.appendChild(button);
    }
  }

  function capture(scope, type, payload) {
    if (type === 'contacts' &&
        Array.isArray(payload)) {

      state.contacts = payload;
    }

    if (type === 'groups' &&
        Array.isArray(payload)) {

      state.groups = payload;
    }

    if (type === 'attachmentHistory' && payload) {
      const chatId = scope === 'GROUP'
        ? payload.groupId
        : payload.peerId || payload.chatId;

      rememberFiles(
        scope,
        chatId,
        payload.items
      );
    }

    if (type === 'attachmentChanged' && payload) {
      const item = payload.item || payload;

      updateFile(
        scope,
        payload.groupId ||
          payload.chatId ||
          payload.peerId ||
          item.chatId,
        item
      );
    }

    setTimeout(() => {
      syncRows();
      refreshButtons();
      renderPlayButtons();
    }, 0);
  }

  function hook(name, scope) {
    const target = window[name];

    if (!target ||
        typeof target.receive !== 'function') {
      return false;
    }

    if (target.receive.__voiceFixed) {
      return true;
    }

    const original = target.receive;

    function wrapped(type, payload) {
      const result = original.apply(this, arguments);
      capture(scope, type, payload);
      return result;
    }

    wrapped.__voiceFixed = true;
    target.receive = wrapped;
    return true;
  }

  // Called by native Java.
  window.LANVoice = {
    receive(type, payload) {
      if (type === 'recordingStarted') {
        state.recording = true;
      }

      if (type === 'processing') {
        state.recording = false;
        state.sending = true;
      }

      if (type === 'sent') {
        reset();
        toast('Voice message queued for delivery');
      }

      if (type === 'cancelled') {
        reset();
      }

      if (type === 'error') {
        reset();
        state.playingId = null;
        toast('Voice: ' + String(payload));
      }

      if (type === 'playbackStopped') {
        state.playingId = null;
      }

      refreshButtons();
    }
  };

  const style = document.createElement('style');
  style.id = 'lan-voice-fixed-style';

  style.textContent = `
    .lan-voice-mic,
    .lan-voice-cancel {
      flex: 0 0 38px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 38px;
      height: 38px;
      border-radius: 11px;
      border: 1px solid rgba(120,120,120,.2);
      background: rgba(120,120,120,.09);
      color: inherit;
      cursor: pointer;
      padding: 0;
    }

    .lan-voice-mic:disabled {
      opacity: .4;
      cursor: not-allowed;
    }

    .lan-voice-mic.lan-voice-recording {
      background: #d43d55;
      color: white;
      border-color: #d43d55;
    }

    .lan-voice-cancel {
      color: #d43d55;
      font-size: 24px;
    }

    .lan-voice-cancel[hidden],
    .lan-voice-status[hidden] {
      display: none !important;
    }

    .lan-voice-status {
      position: fixed;
      bottom: 92px;
      left: 50%;
      transform: translateX(-50%);
      z-index: 25000;
      display: flex;
      gap: 12px;
      align-items: center;
      background: #253149;
      color: #fff;
      padding: 12px 16px;
      border-radius: 13px;
      box-shadow: 0 12px 35px rgba(0,0,0,.3);
      font-size: 13px;
    }

    .lan-voice-status button,
    .lan-voice-play {
      cursor: pointer;
      border-radius: 8px;
      padding: 7px 10px;
      border: 1px solid rgba(160,160,160,.3);
      background: rgba(160,160,160,.13);
      color: inherit;
      font: inherit;
      font-size: 12px;
    }

    .lan-voice-status button {
      color: white;
    }

    .lan-voice-play {
      margin: 5px;
    }
  `;

  document.head.appendChild(style);

  // Keep controls synchronized with live DOM changes.
  setInterval(() => {
    installControls();
    hook('LAN', 'PRIVATE');
    hook('LANGroups', 'GROUP');
    syncRows();
    refreshButtons();

    // Recover from an initial contacts event
    // that occurred before voice module loaded.
    if (
      !$('app')?.classList.contains('hidden') &&
      state.contacts.length === 0
    ) {
      requestContacts();
    }

    if (state.recording && state.startedAt &&
        Date.now() - state.startedAt >= 120000) {
      finish();
    }
  }, 350);

  document.addEventListener('click', () => {
    setTimeout(refreshButtons, 0);
  });

  if ($('search')) {
    $('search').addEventListener(
      'input',
      () => setTimeout(refreshButtons, 0)
    );
  }

  function observeTimeline(id) {
    const root = $(id);
    if (!root) return;

    const observer = new MutationObserver(
      () => setTimeout(renderPlayButtons, 0)
    );

    observer.observe(root, {
      childList: true,
      subtree: true
    });
  }

  observeTimeline('messages');

  const groupObserver = setInterval(() => {
    if ($('group-messages')) {
      observeTimeline('group-messages');
      clearInterval(groupObserver);
    }
  }, 300);

  installControls();

  window.LANVoiceUI = Object.freeze({
    start,
    stopAndSend: finish,
    cancel,
    refresh: refreshButtons
  });

})();
