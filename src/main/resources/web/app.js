
(function () {
  'use strict';

  const $ = id => document.getElementById(id);
  const java = () => window.lanJava;
  const windowApi = () => window.windowControls;

  const state = {
    me: null,
    contacts: [],
    messages: [],
    attachments: [],
    peer: null,
    registering: false,
    theme: 'midnight',
    forwarding: null
  };

  let noticeTimer = null;
  let contextMenu = null;
  let clearDialog = null;

  function notify(message) {
    const node = $('toast');
    node.textContent = String(message);
    node.style.display = 'block';
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => {
      node.style.display = 'none';
    }, 3800);
  }

  function setTheme(name, persist) {
    if (!['midnight', 'aurora', 'frost', 'pearl', 'sunrise'].includes(name)) return;
    state.theme = name;
    document.body.dataset.theme = name;
    document.querySelectorAll('[data-choice]').forEach(button => {
      button.classList.toggle('active', button.dataset.choice === name);
    });
    $('theme-menu').classList.add('hidden');
    if (persist) {
      try {
        if (windowApi()) windowApi().saveTheme(name);
        else if (java()) java().saveTheme(name);
      } catch (e) {
        console.warn('Theme preference:', e);
      }
    }
  }

  function avatar(node, name, image) {
    node.textContent = '';
    node.style.backgroundImage = 'none';
    if (image && /^data:image\/(png|jpeg|webp);base64,/i.test(image)) {
      node.style.backgroundImage = 'url("' + image + '")';
    } else {
      node.textContent = (name || '?').trim().slice(0, 1).toUpperCase();
    }
  }

  function setAuthMode(registering) {
    state.registering = registering;
    $('auth-heading').textContent = registering ? 'Create account' : 'Welcome back';
    $('auth-subtitle').textContent = registering
      ? 'Set up your account on this computer.'
      : 'Sign in to your account on this computer.';
    $('auth-submit').textContent = registering ? 'Create account' : 'Sign in';
    $('switch-mode').textContent = registering
      ? 'Already registered? Sign in' : 'New here? Create an account';
    $('confirm-wrap').classList.toggle('hidden', !registering);
    $('register-avatar').classList.toggle('hidden', !registering);
    $('auth-error').textContent = '';
    $('password').value = '';
    $('confirm').value = '';
    $('password').setAttribute('autocomplete', registering ? 'new-password' : 'current-password');
  }

  function getContact(id) {
    return state.contacts.find(c => c.id === id);
  }

  function renderContacts() {
    const list = $('contacts');
    list.textContent = '';
    const query = $('search').value.toLocaleLowerCase().trim();
    const matches = state.contacts.filter(c => c.username.toLocaleLowerCase().includes(query));
    $('contact-count').textContent = String(state.contacts.length);

    if (!matches.length) {
      const empty = document.createElement('div');
      empty.className = 'sidebar-empty';
      empty.textContent = query ? 'No matching contacts.'
        : 'No contacts found. Make sure LAN Messenger is running on another PC connected to the same network.';
      list.appendChild(empty);
      return;
    }

    for (const c of matches) {
      const button = document.createElement('button');
      button.className = 'contact' + (state.peer === c.id ? ' active' : '');
      const picture = document.createElement('div');
      picture.className = 'avatar small';
      avatar(picture, c.username, c.avatar);
      const copy = document.createElement('div');
      copy.className = 'contact-copy';
      const name = document.createElement('div');
      name.className = 'contact-name';
      name.textContent = c.username;
      const status = document.createElement('div');
      status.className = 'contact-sub' + (c.online ? ' online-text' : '');
      status.textContent = c.online ? 'Online' : 'Offline';
      copy.append(name, status);
      button.append(picture, copy);
      button.onclick = () => openConversation(c.id);
      list.appendChild(button);
    }
  }

  function updateHeader() {
    const peer = getContact(state.peer);
    $('peer-name').textContent = peer ? peer.username : 'Your messages';
    $('peer-status').textContent = !peer ? 'Choose a person to start chatting'
      : peer.online ? 'Online on your LAN' : 'Offline · messages will queue';
    avatar($('peer-avatar'), peer && peer.username, peer && peer.avatar);
    $('chat-menu-button').classList.toggle('hidden', !peer);
    $('composer').classList.toggle('disabled', !peer);
    $('message-input').disabled = !peer;
    $('send').disabled = !peer;
    $('message-input').placeholder = peer
      ? 'Message ' + peer.username + '...' : 'Select a conversation';
  }

  function openConversation(id) {
    if (!getContact(id)) return;
    state.peer = id;
    state.messages = [];
    state.attachments = [];
    closeContext();
    renderContacts();
    updateHeader();
    renderMessages();
    if (java()) java().openConversation(id);
    $('message-input').focus();
  }

  function formatTime(timestamp) {
    const time = new Date(Number(timestamp));
    return Number.isFinite(time.getTime())
      ? time.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
      : '';
  }

  function formatSize(value) {
    const size = Number(value);
    if (!Number.isFinite(size) || size < 0) return 'Unknown size';
    if (size < 1024) return size + ' B';
    if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
    return (size / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function fileSymbol(filename, mimeType) {
    const name = String(filename || '').toLowerCase();
    const mime = String(mimeType || '').toLowerCase();
    if (mime.startsWith('image/') || /\.(png|jpg|jpeg|gif|webp|bmp)$/.test(name)) return '🖼️';
    if (mime.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|aac)$/.test(name)) return '🎵';
    if (mime.startsWith('video/') || /\.(mp4|mkv|mov|avi)$/.test(name)) return '🎬';
    if (/\.(zip|rar|7z|tar|gz)$/.test(name)) return '🗜️';
    if (/\.pdf$/.test(name)) return '📕';
    return '📄';
  }

  function closeContext() {
    if (contextMenu) {
      contextMenu.remove();
      contextMenu = null;
    }
  }

  function openForward(kind, item) {
    closeContext();
    if (!state.contacts.length || !item) {
      notify('No recipients available for forwarding.');
      return;
    }
    if (kind === 'message' && (item.deleted || !item.text)) {
      notify('This message cannot be forwarded.');
      return;
    }
    if (kind === 'attachment' && !item.available) {
      notify('This file is not available on this computer.');
      return;
    }

    state.forwarding = { kind, item };
    const box = $('forward-contacts');
    box.textContent = '';

    for (const contact of state.contacts) {
      const button = document.createElement('button');
      const picture = document.createElement('div');
      picture.className = 'avatar';
      avatar(picture, contact.username, contact.avatar);
      const label = document.createElement('span');
      label.textContent = contact.username + (contact.online ? ' · Online' : ' · Offline');
      button.append(picture, label);
      button.onclick = () => {
        if (!java()) {
          notify('Desktop service is not connected.');
          return;
        }
        if (kind === 'attachment') {
          java().forwardAttachment(item.id, contact.id);
        } else {
          const original = item.text.replace(/^(↪|â†ª) Forwarded\n/, '');
          java().sendMessage(contact.id, '↪ Forwarded\n' + original);
        }
        $('forward-modal').classList.add('hidden');
        state.forwarding = null;
        notify('Forwarding to ' + contact.username + '...');
      };
      box.appendChild(button);
    }

    $('forward-modal').classList.remove('hidden');
  }

  function messageMenu(e, message) {
    e.preventDefault();
    e.stopPropagation();
    closeContext();
    const menu = document.createElement('div');
    menu.className = 'msg-menu glass';

    function action(label, run) {
      const button = document.createElement('button');
      button.textContent = label;
      button.onclick = () => {
        closeContext();
        run();
      };
      menu.appendChild(button);
    }

    if (!message.deleted) action('Forward message', () => openForward('message', message));
    action('Delete for me', () => java() && java().deleteMessage(message.id, false));
    if (state.me && message.senderId === state.me.id && !message.deleted) {
      action('Delete for everyone', () => java() && java().deleteMessage(message.id, true));
    }

    document.body.appendChild(menu);
    const bounds = menu.getBoundingClientRect();
    menu.style.left = Math.max(8, Math.min(e.clientX, window.innerWidth - bounds.width - 10)) + 'px';
    menu.style.top = Math.max(8, Math.min(e.clientY, window.innerHeight - bounds.height - 10)) + 'px';
    contextMenu = menu;
  }

  function attachmentMenu(e, attachment) {
    e.preventDefault();
    e.stopPropagation();
    closeContext();
    const menu = document.createElement('div');
    menu.className = 'msg-menu glass';

    function action(label, run) {
      const button = document.createElement('button');
      button.textContent = label;
      button.onclick = () => {
        closeContext();
        run();
      };
      menu.appendChild(button);
    }

    if (attachment.available) {
      action('Open file', () => java() && java().openAttachment(attachment.id));
      action('Forward file', () => openForward('attachment', attachment));
    }
    action('Delete for me', () => java() && java().deleteAttachment(attachment.id));

    document.body.appendChild(menu);
    const bounds = menu.getBoundingClientRect();
    menu.style.left = Math.max(8, Math.min(e.clientX, window.innerWidth - bounds.width - 10)) + 'px';
    menu.style.top = Math.max(8, Math.min(e.clientY, window.innerHeight - bounds.height - 10)) + 'px';
    contextMenu = menu;
  }

  function addTextMessage(list, message) {
    const mine = state.me && message.senderId === state.me.id;
    const row = document.createElement('div');
    row.className = 'message-row' + (mine ? ' mine' : '');
    const bubble = document.createElement('div');
    bubble.className = 'bubble' + (message.deleted ? ' deleted' : '');
    const text = document.createElement('div');
    text.className = 'bubble-text';

    const raw = String(message.text || '');
    const prefix = ['↪ Forwarded\n', 'â†ª Forwarded\n']
      .find(item => raw.startsWith(item));
    if (!message.deleted && prefix) {
      const badge = document.createElement('div');
      badge.className = 'bubble-forwarded';
      badge.textContent = '↪ Forwarded';
      bubble.appendChild(badge);
    }

    text.textContent = message.deleted
      ? 'This message was deleted'
      : prefix ? raw.slice(prefix.length) : raw;

    const meta = document.createElement('div');
    meta.className = 'bubble-meta';
    meta.textContent = formatTime(message.timestamp)
      + (mine ? ' · ' + String(message.status).toLowerCase() : '');

    const options = document.createElement('button');
    options.className = 'bubble-menu';
    options.textContent = '···';
    options.title = 'Message options';
    options.onclick = e => messageMenu(e, message);

    bubble.append(text, meta, options);
    bubble.addEventListener('contextmenu', e => messageMenu(e, message));
    row.appendChild(bubble);
    list.appendChild(row);
  }

  function addAttachmentMessage(list, attachment) {
    const mine = state.me && attachment.senderId === state.me.id;
    const row = document.createElement('div');
    row.className = 'message-row' + (mine ? ' mine' : '');
    const bubble = document.createElement('div');
    bubble.className = 'bubble lan-file-bubble';

    const contents = document.createElement('div');
    contents.className = 'lan-file-main';
    const symbol = document.createElement('div');
    symbol.className = 'lan-file-symbol';
    symbol.textContent = fileSymbol(attachment.filename, attachment.mimeType);
    const details = document.createElement('div');
    details.className = 'lan-file-details';
    const filename = document.createElement('div');
    filename.className = 'lan-file-name';
    filename.textContent = String(attachment.filename || 'Unnamed file');
    filename.title = filename.textContent;
    const size = document.createElement('div');
    size.className = 'lan-file-size';
    size.textContent = formatSize(attachment.size);
    details.append(filename, size);
    contents.append(symbol, details);

    const actions = document.createElement('div');
    actions.className = 'lan-file-actions';

    function button(label, fn, disabled) {
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'lan-file-button';
      item.textContent = label;
      item.disabled = !!disabled;
      item.onclick = e => {
        e.preventDefault();
        e.stopPropagation();
        fn();
      };
      actions.appendChild(item);
    }

    button('Open file', () => java() && java().openAttachment(attachment.id), !attachment.available);
    button('Forward', () => openForward('attachment', attachment), !attachment.available);

    const menu = document.createElement('button');
    menu.type = 'button';
    menu.className = 'bubble-menu';
    menu.textContent = '···';
    menu.title = 'File options';
    menu.onclick = e => attachmentMenu(e, attachment);

    const meta = document.createElement('div');
    meta.className = 'bubble-meta lan-file-meta';
    const status = mine
      ? 'Queued for delivery'
      : attachment.available ? 'Received' : 'File unavailable';
    meta.textContent = formatTime(attachment.timestamp) + ' · ' + status;

    bubble.append(contents, actions, meta, menu);
    bubble.addEventListener('contextmenu', e => attachmentMenu(e, attachment));
    row.appendChild(bubble);
    list.appendChild(row);
  }

  function renderMessages() {
    const list = $('messages');
    list.textContent = '';

    const timeline = [
      ...state.messages.map(item => ({ kind: 'message', item })),
      ...state.attachments.map(item => ({ kind: 'attachment', item }))
    ];
    timeline.sort((a, b) => {
      const timeA = Number(a.item.timestamp) || 0;
      const timeB = Number(b.item.timestamp) || 0;
      if (timeA !== timeB) return timeA - timeB;
      return String(a.item.id).localeCompare(String(b.item.id));
    });

    if (!state.peer || !timeline.length) {
      const wrapper = document.createElement('div');
      wrapper.className = 'welcome-state';
      if (!state.peer) {
        const image = document.createElement('img');
        image.src = 'logo.svg';
        image.alt = '';
        wrapper.appendChild(image);
      }
      const heading = document.createElement('h2');
      heading.textContent = state.peer ? 'Start a conversation' : 'Your conversations';
      const subtitle = document.createElement('p');
      subtitle.textContent = state.peer
        ? 'Send a message or file to begin your chat. History is stored locally.'
        : 'Choose someone from the sidebar to view or send messages.';
      wrapper.append(heading, subtitle);
      list.appendChild(wrapper);
      return;
    }

    for (const entry of timeline) {
      if (entry.kind === 'attachment') addAttachmentMessage(list, entry.item);
      else addTextMessage(list, entry.item);
    }
    list.scrollTop = list.scrollHeight;
  }

  function updateMessage(message) {
    if (!state.peer || ![message.senderId, message.receiverId].includes(state.peer)) return;
    const index = state.messages.findIndex(m => m.id === message.id);
    if (index === -1) state.messages.push(message);
    else state.messages[index] = message;
    renderMessages();
  }

  function updateAttachment(item) {
    if (!state.peer || item.chatType !== 'PRIVATE' || item.chatId !== state.peer) return;
    const index = state.attachments.findIndex(a => a.id === item.id);
    if (index === -1) state.attachments.push(item);
    else state.attachments[index] = item;
    renderMessages();
  }

  function send() {
    const input = $('message-input');
    const text = input.value.trim();
    if (!state.peer || !text || !java()) return;
    java().sendMessage(state.peer, text);
    input.value = '';
  }

  function dismissClearDialog() {
    if (clearDialog) {
      clearDialog.remove();
      clearDialog = null;
    }
  }

  // Use a reliable in-app dialog instead of WebView confirm().
  function confirmClearChat() {
    if (!state.peer) return;
    dismissClearDialog();
    const peerId = state.peer;
    const contact = getContact(peerId);
    const overlay = document.createElement('div');
    overlay.className = 'lan-confirm-overlay';
    const panel = document.createElement('div');
    panel.className = 'lan-confirm-panel glass';
    const title = document.createElement('h3');
    title.textContent = 'Clear conversation?';
    const text = document.createElement('p');
    text.textContent = 'Remove messages and file cards with '
      + (contact ? contact.username : 'this contact')
      + ' from this computer? Files saved on disk and copies on other PCs are not deleted.';
    const actions = document.createElement('div');
    actions.className = 'lan-confirm-actions';
    const cancel = document.createElement('button');
    cancel.textContent = 'Cancel';
    cancel.onclick = dismissClearDialog;
    const clear = document.createElement('button');
    clear.textContent = 'Clear conversation';
    clear.className = 'lan-confirm-danger';
    clear.onclick = () => {
      dismissClearDialog();
      if (java()) java().clearChat(peerId);
    };
    actions.append(cancel, clear);
    panel.append(title, text, actions);
    overlay.appendChild(panel);
    overlay.onclick = e => {
      if (e.target === overlay) dismissClearDialog();
    };
    document.body.appendChild(overlay);
    clearDialog = overlay;
  }

  function installExtraStyles() {
    if (document.getElementById('lan-attachment-styles')) return;
    const style = document.createElement('style');
    style.id = 'lan-attachment-styles';
    style.textContent = `
      .lan-file-bubble { min-width: 245px; max-width: min(370px, 82vw); padding: 14px 16px; }
      .lan-file-main { display: flex; align-items: center; gap: 12px; padding-right: 18px; }
      .lan-file-symbol { font-size: 27px; width: 38px; flex: 0 0 38px; text-align: center; }
      .lan-file-details { flex: 1; min-width: 0; }
      .lan-file-name { font-size: 13px; font-weight: 700; overflow-wrap: anywhere; }
      .lan-file-size { font-size: 11px; opacity: .75; margin-top: 4px; }
      .lan-file-actions { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 12px; }
      .lan-file-button { border: 1px solid rgba(145,145,145,.32); border-radius: 9px;
        padding: 7px 10px; background: rgba(127,127,127,.14); color: inherit;
        font: inherit; font-size: 12px; cursor: pointer; }
      .lan-file-button:hover:not(:disabled) { background: rgba(127,127,127,.25); }
      .lan-file-button:disabled { opacity: .45; cursor: default; }
      .lan-file-meta { margin-top: 9px; opacity: .78; }
      .lan-confirm-overlay { position: fixed; inset: 0; z-index: 10000;
        background: rgba(0,0,0,.68); display: flex; align-items: center;
        justify-content: center; padding: 20px; }
      .lan-confirm-panel { width: min(410px, 95vw); box-sizing: border-box;
        padding: 24px; border-radius: 17px; background: #202634;
        color: #f5f7ff; box-shadow: 0 15px 50px rgba(0,0,0,.4); }
      .lan-confirm-panel h3 { margin: 0 0 12px; font-size: 19px; }
      .lan-confirm-panel p { font-size: 13px; line-height: 1.55; opacity: .85; }
      .lan-confirm-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 22px; }
      .lan-confirm-actions button { padding: 9px 12px; border-radius: 8px;
        border: 1px solid rgba(150,150,150,.3); background: rgba(150,150,150,.16);
        color: inherit; cursor: pointer; }
      .lan-confirm-actions .lan-confirm-danger { background: #c53b49; border-color: #c53b49; color: white; }
    `;
    document.head.appendChild(style);
  }

  window.LAN = {
    ready() {
      try {
        let choice = 'midnight';
        if (windowApi()) choice = windowApi().getTheme();
        else if (java()) choice = java().getTheme();
        setTheme(String(choice), false);
      } catch (e) {
        setTheme('midnight', false);
      }
    },
    receive(type, data) {
      switch (type) {
        case 'error':
          $('auth-error').textContent = String(data);
          $('auth-submit').disabled = false;
          notify(data);
          break;
        case 'notice': notify(data); break;
        case 'avatarSelected':
          $('avatar-file-label').textContent = data.name;
          break;
        case 'signedIn':
          state.me = data;
          $('auth').classList.add('hidden');
          $('app').classList.remove('hidden');
          $('auth-submit').disabled = false;
          $('my-name').textContent = data.username;
          avatar($('my-avatar'), data.username, data.avatar);
          updateHeader();
          renderMessages();
          break;
        case 'profile':
          state.me = data;
          $('my-name').textContent = data.username;
          avatar($('my-avatar'), data.username, data.avatar);
          break;
        case 'contacts':
          state.contacts = data;
          renderContacts();
          updateHeader();
          break;
        case 'history':
          if (data.peerId === state.peer) {
            state.messages = Array.isArray(data.messages) ? data.messages : [];
            renderMessages();
          }
          break;
        case 'attachmentHistory':
          if (data.peerId === state.peer) {
            state.attachments = Array.isArray(data.items) ? data.items : [];
            renderMessages();
          }
          break;
        case 'attachmentChanged':
          updateAttachment(data);
          break;
        case 'message':
          updateMessage(data);
          break;
        case 'messageDeleted':
          if (state.peer && java()) java().openConversation(state.peer);
          break;
      }
    }
  };

  $('auth-form').onsubmit = e => {
    e.preventDefault();
    const username = $('username').value.trim();
    const password = $('password').value;
    if (state.registering && password !== $('confirm').value) {
      $('auth-error').textContent = 'Passwords do not match.';
      return;
    }
    $('auth-error').textContent = '';
    $('auth-submit').disabled = true;
    if (java()) java().authenticate(state.registering ? 'register' : 'login', username, password);
    else {
      $('auth-submit').disabled = false;
      $('auth-error').textContent = 'Desktop service is not connected.';
    }
  };
  $('switch-mode').onclick = () => setAuthMode(!state.registering);
  $('register-avatar').onclick = () => java() && java().chooseRegistrationPhoto();
  $('change-photo').onclick = () => java() && java().changeAvatar();
  $('refresh').onclick = () => java() && java().loadContacts();
  $('search').oninput = renderContacts;
  $('theme-toggle').onclick = e => {
    e.stopPropagation();
    $('theme-menu').classList.toggle('hidden');
  };
  document.querySelectorAll('[data-choice]').forEach(button => {
    button.onclick = () => setTheme(button.dataset.choice, true);
  });
  $('send').onclick = send;
  $('message-input').onkeydown = e => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  };
  $('attach').onclick = () => {
    if (state.peer && java()) java().attachFile(state.peer);
  };
  $('chat-menu-button').onclick = e => {
    e.stopPropagation();
    $('chat-menu').classList.toggle('hidden');
  };
  $('clear-chat').onclick = () => {
    $('chat-menu').classList.add('hidden');
    confirmClearChat();
  };

  const emojiList = ['😀', '😄', '😂', '😊', '😍', '😎', '👍', '❤️', '🎉', '🙏', '😢', '🔥'];
  for (const emoji of emojiList) {
    const button = document.createElement('button');
    button.textContent = emoji;
    button.onclick = () => {
      const input = $('message-input');
      if (!input.disabled) {
        const start = input.selectionStart;
        const end = input.selectionEnd;
        input.value = input.value.slice(0, start) + emoji + input.value.slice(end);
        input.focus();
      }
      $('emoji-menu').classList.add('hidden');
    };
    $('emoji-menu').appendChild(button);
  }

  $('emoji').onclick = e => {
    e.stopPropagation();
    $('emoji-menu').classList.toggle('hidden');
  };
  $('forward-close').onclick = () => $('forward-modal').classList.add('hidden');
  $('forward-modal').onclick = e => {
    if (e.target.id === 'forward-modal') $('forward-modal').classList.add('hidden');
  };

  document.addEventListener('click', e => {
    if (!e.target.closest('.theme-wrap')) $('theme-menu').classList.add('hidden');
    if (!e.target.closest('#chat-menu-button')) $('chat-menu').classList.add('hidden');
    if (!e.target.closest('#emoji,#emoji-menu')) $('emoji-menu').classList.add('hidden');
    if (contextMenu && !e.target.closest('.msg-menu,.bubble-menu')) closeContext();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      $('forward-modal').classList.add('hidden');
      $('theme-menu').classList.add('hidden');
      $('emoji-menu').classList.add('hidden');
      dismissClearDialog();
      closeContext();
    }
  });

  $('window-min').onclick = () => windowApi() && windowApi().minimize();
  $('window-max').onclick = () => windowApi() && windowApi().maximize();
  $('window-close').onclick = () => windowApi() && windowApi().close();
  let dragging = false;
  $('drag-zone').addEventListener('mousedown', e => {
    if (e.button !== 0 || !windowApi()) return;
    dragging = true;
    windowApi().beginMove(e.screenX, e.screenY);
  });
  $('drag-zone').addEventListener('dblclick', () => windowApi() && windowApi().maximize());
  document.addEventListener('mousemove', e => {
    if (dragging && windowApi()) windowApi().dragMove(e.screenX, e.screenY);
  });
  document.addEventListener('mouseup', () => dragging = false);

  installExtraStyles();
  setAuthMode(false);
  renderMessages();
})();
