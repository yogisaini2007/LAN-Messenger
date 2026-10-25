
/* LAN Messenger - Independent Group Chats UI + Attachments */
(function () {
  'use strict';

  const app = document.getElementById('app');
  const workspace = document.querySelector('.workspace');
  const sidebar = document.querySelector('.sidebar');
  const privateChat = document.querySelector('.conversation');
  const privateList = document.getElementById('contacts');
  const sidebarHeading = sidebar && sidebar.querySelector('.section-title');

  if (!app || !workspace || !sidebar || !privateChat ||
      !privateList || !sidebarHeading) return;

  const $ = id => document.getElementById(id);
  const api = () => window.lanGroups;

  const s = {
    mode: 'private',
    ready: false,
    selfId: '',
    groups: [],
    peers: [],
    members: [],
    groupId: null,
    messages: [],
    attachments: []
  };

  // Private chats remain controlled by app.js.
  const tabs = document.createElement('div');
  tabs.className = 'group-tabs';
  tabs.innerHTML =
    '<button type="button" id="tab-private" class="active">Private</button>' +
    '<button type="button" id="tab-groups">Groups</button>';

  sidebarHeading.before(tabs);

  const createBtn = document.createElement('button');
  createBtn.type = 'button';
  createBtn.className = 'group-new';
  createBtn.textContent = '+ New group';
  createBtn.hidden = true;
  createBtn.onclick = createGroup;
  sidebarHeading.after(createBtn);

  const groupList = document.createElement('div');
  groupList.id = 'group-list';
  groupList.className = 'contacts group-list hidden';
  privateList.after(groupList);

  const groupView = document.createElement('section');
  groupView.className = 'conversation glass group-view hidden';

  groupView.innerHTML =
    '<header class="group-header">' +
      '<div class="group-avatar" id="group-avatar">G</div>' +
      '<div class="group-header-copy">' +
        '<strong id="group-name">Groups</strong>' +
        '<span id="group-subtitle">Choose a group</span>' +
      '</div>' +
      '<button class="group-plain-btn" id="group-manage" type="button" hidden>Manage</button>' +
    '</header>' +
    '<div class="group-messages" id="group-messages"></div>' +
    '<div class="group-composer glass" id="group-composer">' +
      '<textarea id="group-input" rows="2" placeholder="Choose a group" disabled></textarea>' +
      '<button class="group-send" id="group-send" type="button" disabled>Send</button>' +
    '</div>';

  workspace.append(groupView);

  const groupAttach = document.createElement('button');
  groupAttach.type = 'button';
  groupAttach.id = 'group-attach';
  groupAttach.className = 'group-attach';
  groupAttach.textContent = 'Attach';
  groupAttach.title =
    'Share a file; offline group members receive it when they reconnect';

  $('group-composer').insertBefore(
    groupAttach, $('group-input')
  );

  groupAttach.onclick = () => {
    if (!s.groupId || !s.ready || !api()) {
      toast('Select a group first');
      return;
    }
    api().shareGroupFile(s.groupId);
  };

  const modal = document.createElement('div');
  modal.className = 'group-modal-backdrop hidden';
  modal.id = 'group-modal';

  modal.innerHTML =
    '<section class="group-modal glass">' +
      '<div class="group-modal-title">' +
        '<strong id="group-dialog-title">Group</strong>' +
        '<button id="group-modal-close" class="group-x" type="button" aria-label="Close">×</button>' +
      '</div>' +
      '<div id="group-modal-body"></div>' +
    '</section>';

  document.body.append(modal);

  $('group-modal-close').onclick = () => {
    modal.classList.add('hidden');
  };

  modal.addEventListener('click', e => {
    if (e.target === modal) modal.classList.add('hidden');
  });

  function toast(text) {
    const node = $('toast');
    if (!node) return;

    node.textContent = String(text);
    node.style.display = 'block';

    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => {
      node.style.display = 'none';
    }, 3800);
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);

    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);

    return node;
  }

  function groupById(id) {
    return s.groups.find(g => g.id === id);
  }

  function picture(node, group) {
    const name = group && group.name ? group.name : 'G';

    node.textContent = name.slice(0, 1).toUpperCase();
    node.style.backgroundImage = 'none';

    if (
      group &&
      group.avatar &&
      group.avatar.startsWith('data:image/png;base64,')
    ) {
      node.style.backgroundImage =
        'url("' + group.avatar + '")';
      node.textContent = '';
    }
  }

  function switchMode(mode) {
    s.mode = mode;

    const isGroup = mode === 'groups';

    $('tab-private').classList.toggle('active', !isGroup);
    $('tab-groups').classList.toggle('active', isGroup);

    privateList.classList.toggle('hidden', isGroup);
    groupList.classList.toggle('hidden', !isGroup);
    createBtn.hidden = !isGroup;

    privateChat.classList.toggle('hidden', isGroup);
    groupView.classList.toggle('hidden', !isGroup);

    const heading = sidebarHeading.querySelector('span');

    if (heading) {
      heading.textContent = isGroup ? 'Groups' : 'Chats';
    }

    if (isGroup) {
      if (api()) api().refresh();
      renderGroups();
    }
  }

  $('tab-private').onclick = () => switchMode('private');
  $('tab-groups').onclick = () => switchMode('groups');

  function renderGroups() {
    groupList.replaceChildren();

    if (!s.groups.length) {
      groupList.append(
        el(
          'div',
          'sidebar-empty',
          'No groups yet. Create a group with people on your LAN.'
        )
      );
      return;
    }

    for (const group of s.groups) {
      const item = el(
        'button',
        'group-contact' +
          (s.groupId === group.id ? ' active' : '')
      );

      item.type = 'button';

      const avatar = el('div', 'group-avatar');
      picture(avatar, group);

      item.append(
        avatar,
        el('strong', '', group.name)
      );

      item.onclick = () => openGroup(group.id);

      groupList.append(item);
    }
  }

  function openGroup(groupId) {
    s.groupId = groupId;
    s.messages = [];
    s.attachments = [];
    s.members = [];

    renderGroups();
    renderHeader();
    renderMessages();

    if (api()) api().openGroup(groupId);

    $('group-input').focus();
  }

  function renderHeader() {
    const group = groupById(s.groupId);

    $('group-name').textContent =
      group ? group.name : 'Groups';

    $('group-subtitle').textContent = !group
      ? 'Select a group'
      : s.members.length
        ? s.members.length + ' members'
        : 'Loading members...';

    picture($('group-avatar'), group);

    $('group-manage').hidden = !group;
    $('group-input').disabled = !group;
    $('group-send').disabled = !group;
    groupAttach.disabled = !group;

    $('group-input').placeholder = group
      ? 'Message ' + group.name
      : 'Choose a group';
  }

  function formatTime(time) {
    const d = new Date(Number(time));

    return Number.isNaN(d.getTime())
      ? ''
      : d.toLocaleTimeString([], {
          hour: '2-digit',
          minute: '2-digit'
        });
  }

  function formatSize(value) {
    const n = Number(value);

    if (!Number.isFinite(n) || n < 0) {
      return 'Unknown size';
    }

    if (n < 1024) return n + ' B';

    if (n < 1024 * 1024) {
      return (n / 1024).toFixed(1) + ' KB';
    }

    return (n / 1048576).toFixed(1) + ' MB';
  }

  function fileIcon(file) {
    const name = String(file.filename || '').toLowerCase();
    const mime = String(file.mimeType || '').toLowerCase();

    if (
      mime.startsWith('image/') ||
      /\.(png|jpg|jpeg|gif|webp)$/.test(name)
    ) {
      return '🖼️';
    }

    if (
      mime.startsWith('audio/') ||
      /\.(mp3|wav|ogg|m4a)$/.test(name)
    ) {
      return '🎵';
    }

    if (
      mime.startsWith('video/') ||
      /\.(mp4|avi|mkv|mov)$/.test(name)
    ) {
      return '🎬';
    }

    if (/\.pdf$/.test(name)) return '📕';

    if (/\.(zip|rar|7z|tar|gz)$/.test(name)) {
      return '🗜️';
    }

    return '📄';
  }

  function memberName(id) {
    const person = s.members.find(m => m.id === id);
    const online = s.peers.find(p => p.id === id);

    if (id === s.selfId) return 'You';
    if (person) return person.name;
    if (online) return online.username;

    return 'Group member';
  }

  function renderTextMessage(root, message) {
    const mine = message.senderId === s.selfId;

    const row = el(
      'div',
      'group-message-row' + (mine ? ' mine' : '')
    );

    const bubble = el('div', 'group-bubble');

    bubble.append(
      el(
        'div',
        'group-sender',
        memberName(message.senderId)
      )
    );

    bubble.append(
      el(
        'div',
        'group-message-text' +
          (message.deleted ? ' deleted' : ''),
        message.deleted
          ? 'This message was deleted'
          : message.text
      )
    );

    const footer = el(
      'div',
      'group-message-meta',
      formatTime(message.timestamp)
    );

    const menu = el(
      'button',
      'group-bubble-menu',
      '···'
    );

    menu.type = 'button';
    menu.title = 'Message options';

    menu.onclick = e => {
      e.stopPropagation();
      showMessageActions(message);
    };

    footer.append(menu);
    bubble.append(footer);
    row.append(bubble);

    root.append(row);
  }

  function renderAttachment(root, file) {
    const mine = file.senderId === s.selfId;

    const row = el(
      'div',
      'group-message-row' + (mine ? ' mine' : '')
    );

    const bubble = el(
      'div',
      'group-bubble group-file-bubble'
    );

    bubble.append(
      el(
        'div',
        'group-sender',
        memberName(file.senderId)
      )
    );

    const main = el('div', 'group-file-main');

    main.append(
      el('span', 'group-file-icon', fileIcon(file))
    );

    const details = el('div', 'group-file-details');

    const name = el(
      'div',
      'group-file-name',
      file.filename || 'Unnamed file'
    );

    name.title = file.filename || '';

    details.append(
      name,
      el(
        'div',
        'group-file-size',
        formatSize(file.size)
      )
    );

    main.append(details);
    bubble.append(main);

    const actions = el('div', 'group-file-actions');

    const open = el(
      'button',
      'group-file-button',
      'Open file'
    );

    open.type = 'button';
    open.disabled = !file.available;

    open.onclick = () => {
      if (api()) api().openGroupAttachment(file.id);
    };

    const forward = el(
      'button',
      'group-file-button',
      'Forward'
    );

    forward.type = 'button';
    forward.disabled = !file.available;
    forward.onclick = () => showForwardFile(file);

    actions.append(open, forward);
    bubble.append(actions);

    const status = mine
      ? 'Queued / delivery status pending'
      : file.available
        ? 'Received'
        : 'File unavailable';

    const footer = el(
      'div',
      'group-message-meta',
      formatTime(file.timestamp) + ' · ' + status
    );

    const options = el(
      'button',
      'group-bubble-menu',
      '···'
    );

    options.type = 'button';
    options.title = 'File options';

    options.onclick = e => {
      e.stopPropagation();
      showFileActions(file);
    };

    footer.append(options);
    bubble.append(footer);

    row.append(bubble);
    root.append(row);
  }

  function renderMessages() {
    const root = $('group-messages');
    root.replaceChildren();

    const timeline = [
      ...s.messages.map(item => ({
        kind: 'message',
        item
      })),
      ...s.attachments.map(item => ({
        kind: 'attachment',
        item
      }))
    ];

    timeline.sort((a, b) =>
      (Number(a.item.timestamp) || 0) -
      (Number(b.item.timestamp) || 0) ||
      String(a.item.id).localeCompare(
        String(b.item.id)
      )
    );

    if (!s.groupId || !timeline.length) {
      root.append(
        el(
          'div',
          'group-empty',
          s.groupId
            ? 'No messages or files yet. Start the conversation.'
            : 'Choose a group to start chatting.'
        )
      );
      return;
    }

    for (const entry of timeline) {
      if (entry.kind === 'attachment') {
        renderAttachment(root, entry.item);
      } else {
        renderTextMessage(root, entry.item);
      }
    }

    root.scrollTop = root.scrollHeight;
  }

  function send() {
    if (!s.groupId || !api()) return;

    const input = $('group-input');
    const text = input.value.trim();

    if (!text) return;

    api().sendMessage(s.groupId, text);
    input.value = '';
  }

  // Enter inserts a newline.
  // Only the Send button sends a text message.
  $('group-send').onclick = send;

  function showDialog(title) {
    $('group-dialog-title').textContent = title;

    const body = $('group-modal-body');
    body.replaceChildren();

    modal.classList.remove('hidden');
    return body;
  }

  function makeButton(text, parent, onClick, danger) {
    const button = el(
      'button',
      'group-action' + (danger ? ' danger' : ''),
      text
    );

    button.type = 'button';
    button.onclick = onClick;

    parent.append(button);
    return button;
  }

  function createGroup() {
    if (!s.ready) {
      toast('Group service is not ready');
      return;
    }

    const body = showDialog('Create group');

    const input = el('input', 'group-field');
    input.placeholder = 'Group name';
    input.maxLength = 80;

    body.append(
      input,
      el(
        'div',
        'group-section-label',
        'Select members'
      )
    );

    const selected = new Set();

    for (const peer of s.peers) {
      const label = el('label', 'group-select-row');
      const check = document.createElement('input');

      check.type = 'checkbox';

      check.onchange = () => {
        if (check.checked) {
          selected.add(peer.id);
        } else {
          selected.delete(peer.id);
        }
      };

      label.append(
        check,
        el('span', '', peer.username)
      );

      body.append(label);
    }

    if (!s.peers.length) {
      body.append(
        el(
          'p',
          'group-hint',
          'No online peers detected.'
        )
      );
    }

    makeButton('Create group', body, () => {
      if (!input.value.trim() || !selected.size) {
        toast(
          'Enter a name and select at least one member'
        );
        return;
      }

      api().createGroup(
        input.value.trim(),
        [...selected].join(',')
      );

      modal.classList.add('hidden');
    });
  }

  function showMessageActions(message) {
    const body = showDialog('Message options');

    makeButton('Delete for me', body, () => {
      if (api()) {
        api().deleteMessage(message.id, false);
      }
      modal.classList.add('hidden');
    });

    if (
      message.senderId === s.selfId &&
      !message.deleted
    ) {
      makeButton(
        'Delete for everyone',
        body,
        () => {
          if (api()) {
            api().deleteMessage(
              message.id,
              true
            );
          }
          modal.classList.add('hidden');
        },
        true
      );
    }
  }

  function showFileActions(file) {
    const groupId = s.groupId;
    const body = showDialog('File options');

    body.append(
      el(
        'p',
        'group-hint',
        file.filename || 'Attachment'
      )
    );

    if (file.available) {
      makeButton('Open file', body, () => {
        modal.classList.add('hidden');

        if (api()) {
          api().openGroupAttachment(file.id);
        }
      });

      makeButton(
        'Forward to another group',
        body,
        () => showForwardFile(file)
      );
    }

    makeButton(
      'Delete for me',
      body,
      () => {
        modal.classList.add('hidden');

        if (api()) {
          api().deleteGroupAttachment(
            groupId,
            file.id
          );
        }
      },
      true
    );
  }

  function showForwardFile(file) {
    if (!file.available) {
      toast(
        'This attachment is unavailable on this computer'
      );
      return;
    }

    const sourceGroupId = s.groupId;

    const targets = s.groups.filter(
      group => group.id !== sourceGroupId
    );

    const body = showDialog('Forward file to group');

    body.append(
      el(
        'p',
        'group-hint',
        file.filename || 'Attachment'
      )
    );

    if (!targets.length) {
      body.append(
        el(
          'p',
          'group-hint',
          'No other group available for forwarding.'
        )
      );
      return;
    }

    for (const target of targets) {
      makeButton(
        target.name,
        body,
        () => {
          modal.classList.add('hidden');

          if (api()) {
            api().forwardGroupAttachment(
              sourceGroupId,
              file.id,
              target.id
            );
          }

          toast(
            'File queued for forwarding to ' +
            target.name
          );
        }
      );
    }
  }

  function manage() {
    const group = groupById(s.groupId);

    if (!group) return;

    const body = showDialog(
      'Manage ' + group.name
    );

    const mine = s.members.find(
      m => m.id === s.selfId
    );

    const owner =
      mine && mine.role === 'OWNER';

    const manager =
      owner ||
      (mine && mine.role === 'ADMIN');

    body.append(
      el(
        'div',
        'group-section-label',
        'Members'
      )
    );

    for (const member of s.members) {
      const row = el(
        'div',
        'group-member-row'
      );

      row.append(
        el('strong', '', member.name),
        el('small', '', member.role)
      );

      if (
        owner &&
        member.id !== s.selfId
      ) {
        makeButton(
          member.role === 'ADMIN'
            ? 'Demote'
            : 'Make admin',
          row,
          () => {
            if (api()) {
              api().setAdmin(
                group.id,
                member.id,
                member.role !== 'ADMIN'
              );
            }

            modal.classList.add('hidden');
          }
        );

        makeButton(
          'Remove',
          row,
          () => {
            if (api()) {
              api().removeMember(
                group.id,
                member.id
              );
            }

            modal.classList.add('hidden');
          },
          true
        );
      }

      body.append(row);
    }

    if (manager) {
      body.append(
        el(
          'div',
          'group-section-label',
          'Group details'
        )
      );

      const rename = el(
        'input',
        'group-field'
      );

      rename.value = group.name;
      body.append(rename);

      makeButton(
        'Rename group',
        body,
        () => {
          if (api()) {
            api().renameGroup(
              group.id,
              rename.value.trim()
            );
          }

          modal.classList.add('hidden');
        }
      );

      makeButton(
        'Change group photo',
        body,
        () => {
          if (api()) {
            api().changeGroupAvatar(group.id);
          }

          modal.classList.add('hidden');
        }
      );
    }

    if (owner) {
      body.append(
        el(
          'div',
          'group-section-label',
          'Add online member'
        )
      );

      const available = s.peers.filter(
        peer =>
          !s.members.some(
            member => member.id === peer.id
          )
      );

      for (const peer of available) {
        makeButton(
          'Add ' + peer.username,
          body,
          () => {
            if (api()) {
              api().addMember(
                group.id,
                peer.id
              );
            }

            modal.classList.add('hidden');
          }
        );
      }
    } else {
      makeButton(
        'Leave group',
        body,
        () => {
          if (api()) {
            api().leaveGroup(group.id);
          }

          modal.classList.add('hidden');
        },
        true
      );
    }
  }

  $('group-manage').onclick = manage;

  // Group events are independent of private window.LAN.
  window.LANGroups = {
    receive(type, payload) {

      if (type === 'notice') {
        toast(payload);
        return;
      }

      if (type === 'error') {
        toast('Groups: ' + payload);
        return;
      }

      if (type === 'ready') {
        s.ready = true;

        if (payload && payload.selfId) {
          s.selfId = payload.selfId;
        }
      }

      if (type === 'peers') {
        s.peers = Array.isArray(payload)
          ? payload
          : [];
      }

      if (type === 'groups') {
        s.groups = Array.isArray(payload)
          ? payload
          : [];

        if (
          s.groupId &&
          !groupById(s.groupId)
        ) {
          s.groupId = null;
          s.members = [];
          s.messages = [];
          s.attachments = [];
        }

        renderGroups();
        renderHeader();
        renderMessages();

        if (s.groupId && api()) {
          api().openGroup(s.groupId);
        }
      }

      if (type === 'created') {
        openGroup(payload);
      }

      if (
        type === 'updated' &&
        payload === s.groupId &&
        api()
      ) {
        api().openGroup(s.groupId);
      }

      if (
        type === 'members' &&
        payload &&
        payload.groupId === s.groupId
      ) {
        s.members = Array.isArray(payload.members)
          ? payload.members
          : [];

        renderHeader();
        renderMessages();
      }

      if (
        type === 'history' &&
        payload &&
        payload.groupId === s.groupId
      ) {
        s.messages = Array.isArray(payload.messages)
          ? payload.messages
          : [];

        renderMessages();
      }

      if (
        type === 'attachmentHistory' &&
        payload &&
        payload.groupId === s.groupId
      ) {
        s.attachments = Array.isArray(payload.items)
          ? payload.items
          : [];

        renderMessages();
      }

      if (
        type === 'attachmentChanged' &&
        payload &&
        payload.groupId === s.groupId
      ) {
        const index = s.attachments.findIndex(
          a => a.id === payload.id
        );

        if (index < 0) {
          s.attachments.push(payload);
        } else {
          s.attachments[index] = payload;
        }

        renderMessages();
      }

      if (
        type === 'message' &&
        payload &&
        payload.groupId === s.groupId
      ) {
        const index = s.messages.findIndex(
          m => m.id === payload.id
        );

        if (index < 0) {
          s.messages.push(payload);
        } else {
          s.messages[index] = payload;
        }

        s.messages.sort(
          (a, b) => a.timestamp - b.timestamp
        );

        renderMessages();
      }

      if (
        type === 'deleted' &&
        s.groupId &&
        api()
      ) {
        api().openGroup(s.groupId);
      }
    }
  };

  // Extra styles apply only to group attachment cards.
  const style = document.createElement('style');
  style.id = 'lan-group-file-styles';

  style.textContent = `
    .group-file-bubble {
      min-width: 235px;
      max-width: min(380px, 80vw);
    }

    .group-file-main {
      display: flex;
      align-items: center;
      gap: 12px;
      margin: 8px 0;
    }

    .group-file-icon {
      font-size: 27px;
      flex: 0 0 37px;
      text-align: center;
    }

    .group-file-details {
      flex: 1;
      min-width: 0;
    }

    .group-file-name {
      overflow-wrap: anywhere;
      font-size: 13px;
      font-weight: 650;
    }

    .group-file-size {
      font-size: 11px;
      opacity: .75;
      padding-top: 3px;
    }

    .group-file-actions {
      display: flex;
      gap: 7px;
      flex-wrap: wrap;
      margin: 9px 0;
    }

    .group-file-button {
      font: inherit;
      font-size: 12px;
      border-radius: 8px;
      border: 1px solid rgba(140,140,140,.35);
      background: rgba(125,125,125,.12);
      color: inherit;
      padding: 7px 10px;
      cursor: pointer;
    }

    .group-file-button:hover:not(:disabled) {
      background: rgba(125,125,125,.23);
    }

    .group-file-button:disabled {
      opacity: .45;
      cursor: default;
    }
  `;

  document.head.append(style);

  // Activate optional group networking after account login.
  // Group errors must never stop private messaging.
  let requested = false;

  function maybeStart() {
    if (
      !requested &&
      !app.classList.contains('hidden') &&
      api()
    ) {
      requested = true;
      api().start();
    }
  }

  const observer = new MutationObserver(maybeStart);

  observer.observe(app, {
    attributes: true,
    attributeFilter: ['class']
  });

  maybeStart();
  renderHeader();
  renderMessages();

})();
