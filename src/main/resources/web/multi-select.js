
/*
 * LAN Messenger - Modular Multi-Selection Controller
 * Private and group chat bulk Delete / Forward.
 *
 * Does not replace app.js or groups.js.
 */
(function () {
  'use strict';

  const $ = id => document.getElementById(id);

  const privateChats = new Map();
  const groupChats = new Map();
  const rowData = new WeakMap();
  const selected = new Map();

  let contacts = [];
  let groups = [];
  let selfId = '';
  let holdTimer = null;
  let holdPosition = null;
  let suppressClick = false;
  let toolbar = null;
  let overlay = null;
  let watching = false;

  function privateApi() {
    return window.lanJava;
  }

  function groupApi() {
    return window.lanGroups;
  }

  function store(map, id) {
    if (!map.has(id)) {
      map.set(id, {
        messages: [],
        attachments: []
      });
    }
    return map.get(id);
  }

  function updateItem(list, item) {
    if (!item || !item.id) return;

    const index = list.findIndex(x => x.id === item.id);

    if (index < 0) list.push(item);
    else list[index] = item;
  }

  function capturePrivate(type, data) {
    if (!data) return;

    if (type === 'signedIn' || type === 'profile') {
      selfId = data.id || selfId;
    }

    if (type === 'contacts') {
      contacts = Array.isArray(data) ? data : [];
    }

    if (type === 'history' && data.peerId) {
      store(privateChats, data.peerId).messages =
        Array.isArray(data.messages) ? data.messages.slice() : [];
    }

    if (type === 'attachmentHistory' && data.peerId) {
      store(privateChats, data.peerId).attachments =
        Array.isArray(data.items) ? data.items.slice() : [];
    }

    if (type === 'message' && data.id) {
      const peerId = data.senderId === selfId
        ? data.receiverId : data.senderId;

      if (peerId) {
        updateItem(store(privateChats, peerId).messages, data);
      }
    }

    if (type === 'attachmentChanged' &&
        data.chatType === 'PRIVATE' && data.chatId) {
      updateItem(
        store(privateChats, data.chatId).attachments,
        data
      );
    }

    if (type === 'messageDeleted') {
      // The existing bridge reloads conversation history.
      // Do not guess which conversation was modified.
    }
  }

  function captureGroup(type, data) {
    if (!data) return;

    if (type === 'ready') {
      selfId = data.selfId || selfId;
    }

    if (type === 'groups') {
      groups = Array.isArray(data) ? data : [];
    }

    if (type === 'history' && data.groupId) {
      store(groupChats, data.groupId).messages =
        Array.isArray(data.messages) ? data.messages.slice() : [];
    }

    if (type === 'attachmentHistory' && data.groupId) {
      store(groupChats, data.groupId).attachments =
        Array.isArray(data.items) ? data.items.slice() : [];
    }

    if (type === 'message' && data.groupId) {
      updateItem(store(groupChats, data.groupId).messages, data);
    }

    if (type === 'attachmentChanged' && data.groupId) {
      updateItem(
        store(groupChats, data.groupId).attachments,
        data
      );
    }
  }

  function installEventHooks() {
    let attachedPrivate = false;
    let attachedGroup = false;

    const timer = setInterval(() => {
      if (!attachedPrivate && window.LAN &&
          typeof window.LAN.receive === 'function') {
        const original = window.LAN.receive;

        window.LAN.receive = function (type, data) {
          capturePrivate(type, data);
          const result = original.call(window.LAN, type, data);
          setTimeout(refreshRows, 0);
          return result;
        };

        attachedPrivate = true;
      }

      if (!attachedGroup && window.LANGroups &&
          typeof window.LANGroups.receive === 'function') {
        const original = window.LANGroups.receive;

        window.LANGroups.receive = function (type, data) {
          captureGroup(type, data);
          const result = original.call(window.LANGroups, type, data);
          setTimeout(refreshRows, 0);
          return result;
        };

        attachedGroup = true;
      }

      if (attachedPrivate && attachedGroup) {
        clearInterval(timer);
      }
    }, 100);
  }

  function currentContext() {
    const groupView = document.querySelector('.group-view');

    if (groupView && !groupView.classList.contains('hidden')) {
      const active = document.querySelector(
        '#group-list .group-contact.active'
      );

      if (!active) return null;

      const nameNode = active.querySelector('strong');
      if (!nameNode) return null;

      const matches = groups.filter(
        g => g.name === nameNode.textContent
      );

      if (matches.length !== 1) return null;

      return {
        mode: 'groups',
        id: matches[0].id
      };
    }

    const active = document.querySelector(
      '#contacts .contact.active'
    );

    if (!active) return null;

    const nameNode = active.querySelector('.contact-name');
    if (!nameNode) return null;

    const matches = contacts.filter(
      c => c.username === nameNode.textContent
    );

    if (matches.length !== 1) return null;

    return {
      mode: 'private',
      id: matches[0].id
    };
  }

  function contextKey(context) {
    return context.mode + ':' + context.id;
  }

  function timeline(context) {
    const source = context.mode === 'private'
      ? store(privateChats, context.id)
      : store(groupChats, context.id);

    const items = [];

    for (const message of source.messages) {
      items.push({
        kind: 'message',
        item: message
      });
    }

    for (const attachment of source.attachments) {
      items.push({
        kind: 'attachment',
        item: attachment
      });
    }

    items.sort((a, b) => {
      const diff = Number(a.item.timestamp || 0)
        - Number(b.item.timestamp || 0);

      return diff || String(a.item.id).localeCompare(
        String(b.item.id)
      );
    });

    return items;
  }

  function rowElements(context) {
    const root = context.mode === 'private'
      ? $('messages') : $('group-messages');

    if (!root) return [];

    const className = context.mode === 'private'
      ? 'message-row' : 'group-message-row';

    return Array.from(root.children).filter(
      child => child.classList.contains(className)
    );
  }

  function makeKey(context, entry) {
    return contextKey(context) + ':' +
      entry.kind + ':' + entry.item.id;
  }

  function refreshRows() {
    const context = currentContext();
    if (!context) return;

    const entries = timeline(context);
    const rows = rowElements(context);

    // Never attach selection IDs to mismatched rows.
    if (rows.length !== entries.length) return;

    for (let i = 0; i < rows.length; i++) {
      const key = makeKey(context, entries[i]);

      rowData.set(rows[i], {
        key: key,
        context: context,
        kind: entries[i].kind,
        item: entries[i].item
      });

      rows[i].classList.toggle(
        'lan-bulk-selected',
        selected.has(key)
      );
    }
  }

  function findRow(element) {
    if (!element || !element.closest) return null;

    return element.closest(
      '#messages .message-row, ' +
      '#group-messages .group-message-row'
    );
  }

  function showToast(text) {
    const node = $('toast');
    if (!node) return;

    node.textContent = text;
    node.style.display = 'block';

    setTimeout(() => {
      if (node.textContent === text) {
        node.style.display = 'none';
      }
    }, 3500);
  }

  function clearSelection() {
    selected.clear();

    if (toolbar) {
      toolbar.remove();
      toolbar = null;
    }

    refreshRows();
  }

  function toggleRow(row) {
    const entry = rowData.get(row);
    if (!entry) return;

    const active = currentContext();
    if (!active ||
        contextKey(active) !== contextKey(entry.context)) {
      return;
    }

    if (selected.size) {
      const first = selected.values().next().value;

      if (contextKey(first.context) !== contextKey(active)) {
        clearSelection();
      }
    }

    if (selected.has(entry.key)) {
      selected.delete(entry.key);
    } else {
      selected.set(entry.key, entry);
    }

    if (!selected.size) {
      clearSelection();
    } else {
      showToolbar();
      refreshRows();
    }
  }

  function makeButton(text, handler) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = text;
    button.onclick = handler;
    return button;
  }

  function showToolbar() {
    if (!toolbar) {
      toolbar = document.createElement('div');
      toolbar.className = 'lan-bulk-toolbar';

      const count = document.createElement('span');
      count.id = 'lan-bulk-count';

      const deleteButton = makeButton(
        'Delete',
        confirmDelete
      );

      const forwardButton = makeButton(
        'Forward',
        showForwardOptions
      );

      const cancelButton = makeButton(
        'Cancel',
        clearSelection
      );

      toolbar.append(
        count,
        deleteButton,
        forwardButton,
        cancelButton
      );

      document.body.appendChild(toolbar);
    }

    $('lan-bulk-count').textContent =
      selected.size + ' selected';
  }

  function dismissDialog() {
    if (overlay) {
      overlay.remove();
      overlay = null;
    }
  }

  function dialog(title, description) {
    dismissDialog();

    overlay = document.createElement('div');
    overlay.className = 'lan-bulk-overlay';

    const panel = document.createElement('div');
    panel.className = 'lan-bulk-dialog';

    const heading = document.createElement('h3');
    heading.textContent = title;

    const explanation = document.createElement('p');
    explanation.textContent = description;

    panel.append(heading, explanation);
    overlay.appendChild(panel);

    overlay.onclick = event => {
      if (event.target === overlay) dismissDialog();
    };

    document.body.appendChild(overlay);
    return panel;
  }

  function confirmDelete() {
    if (!selected.size) return;

    const entries = Array.from(selected.values());

    const panel = dialog(
      'Delete selected items?',
      'Remove ' + entries.length +
      ' selected messages or file cards from this computer. ' +
      'This will not delete copies from other computers.'
    );

    const actions = document.createElement('div');
    actions.className = 'lan-bulk-dialog-actions';

    actions.append(
      makeButton('Cancel', dismissDialog),
      makeButton('Delete for me', () => {
        dismissDialog();

        const p = privateApi();
        const g = groupApi();

        let requested = 0;

        for (const entry of entries) {
          try {
            if (entry.context.mode === 'private' && p) {
              if (entry.kind === 'attachment') {
                p.deleteAttachment(entry.item.id);
              } else {
                p.deleteMessage(entry.item.id, false);
              }
              requested++;
            } else if (
              entry.context.mode === 'groups' && g
            ) {
              if (entry.kind === 'attachment') {
                g.deleteGroupAttachment(
                  entry.context.id,
                  entry.item.id
                );
              } else {
                g.deleteMessage(entry.item.id, false);
              }
              requested++;
            }
          } catch (error) {
            console.error('Bulk delete:', error);
          }
        }

        clearSelection();
        showToast(
          'Delete requested for ' + requested + ' items.'
        );
      })
    );

    panel.append(actions);
  }

  function showForwardOptions() {
    if (!selected.size) return;

    const entries = Array.from(selected.values());
    const source = entries[0].context;

    const valid = entries.filter(entry => {
      if (entry.kind === 'attachment') {
        return !!entry.item.available;
      }

      return !entry.item.deleted &&
        !!entry.item.text;
    });

    if (!valid.length) {
      showToast('No selected items can be forwarded.');
      return;
    }

    const destinations = source.mode === 'private'
      ? contacts.filter(c => c.id !== source.id)
          .map(c => ({ id: c.id, name: c.username }))
      : groups.filter(g => g.id !== source.id)
          .map(g => ({ id: g.id, name: g.name }));

    const panel = dialog(
      'Forward selected items',
      'Select a destination for ' + valid.length +
      ' message(s) or available file(s).'
    );

    if (!destinations.length) {
      const note = document.createElement('p');
      note.textContent = 'No other destinations available.';
      panel.append(note);
      panel.append(makeButton('Close', dismissDialog));
      return;
    }

    const list = document.createElement('div');
    list.className = 'lan-bulk-destinations';

    for (const destination of destinations) {
      list.append(makeButton(destination.name, () => {
        const p = privateApi();
        const g = groupApi();

        let requested = 0;

        for (const entry of valid) {
          try {
            if (source.mode === 'private' && p) {
              if (entry.kind === 'attachment') {
                p.forwardAttachment(
                  entry.item.id,
                  destination.id
                );
              } else {
                const body = String(entry.item.text)
                  .replace(/^(↪|â†ª) Forwarded\n/, '');

                p.sendMessage(
                  destination.id,
                  '↪ Forwarded\n' + body
                );
              }
              requested++;
            } else if (source.mode === 'groups' && g) {
              if (entry.kind === 'attachment') {
                g.forwardGroupAttachment(
                  source.id,
                  entry.item.id,
                  destination.id
                );
              } else {
                g.sendMessage(
                  destination.id,
                  '↪ Forwarded\n' + entry.item.text
                );
              }
              requested++;
            }
          } catch (error) {
            console.error('Bulk forward:', error);
          }
        }

        dismissDialog();
        clearSelection();

        showToast(
          'Forward requested for ' + requested + ' items.'
        );
      }));
    }

    panel.append(list);
    panel.append(makeButton('Cancel', dismissDialog));
  }

  function isControl(element) {
    return !!(
      element.closest &&
      element.closest(
        'button, input, textarea, a, select'
      )
    );
  }

  function beginHold(event, x, y) {
    cancelHold();

    if (isControl(event.target)) return;

    refreshRows();

    const row = findRow(event.target);

    if (!row || !rowData.has(row)) return;

    holdPosition = { x, y };

    holdTimer = setTimeout(() => {
      holdTimer = null;

      if (!document.body.contains(row)) return;

      suppressClick = true;
      toggleRow(row);
    }, 550);
  }

  function cancelHold() {
    if (holdTimer !== null) {
      clearTimeout(holdTimer);
      holdTimer = null;
    }

    holdPosition = null;
  }

  document.addEventListener('mousedown', event => {
    if (event.button !== 0) return;

    beginHold(
      event,
      event.clientX,
      event.clientY
    );
  }, true);

  document.addEventListener('mousemove', event => {
    if (!holdPosition) return;

    const dx = event.clientX - holdPosition.x;
    const dy = event.clientY - holdPosition.y;

    if (dx * dx + dy * dy > 100) {
      cancelHold();
    }
  }, true);

  document.addEventListener(
    'mouseup',
    cancelHold,
    true
  );

  document.addEventListener('touchstart', event => {
    if (!event.touches.length) return;

    beginHold(
      event,
      event.touches[0].clientX,
      event.touches[0].clientY
    );
  }, true);

  document.addEventListener('touchmove', event => {
    if (!holdPosition || !event.touches.length) return;

    const dx =
      event.touches[0].clientX - holdPosition.x;

    const dy =
      event.touches[0].clientY - holdPosition.y;

    if (dx * dx + dy * dy > 100) {
      cancelHold();
    }
  }, true);

  document.addEventListener(
    'touchend',
    cancelHold,
    true
  );

  document.addEventListener('click', event => {
    const row = findRow(event.target);

    if (row && suppressClick) {
      suppressClick = false;
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }

    if (!selected.size || !row) return;

    event.preventDefault();
    event.stopImmediatePropagation();
    toggleRow(row);
  }, true);

  // Changing chats clears selection.
  document.addEventListener('click', event => {
    if (event.target.closest(
      '.contact, .group-contact, ' +
      '#tab-private, #tab-groups'
    )) {
      clearSelection();
    }
  }, true);

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      dismissDialog();
      clearSelection();
    }
  });

  function injectStyles() {
    const style = document.createElement('style');
    style.id = 'lan-multi-selection-styles';

    style.textContent = `
      .lan-bulk-selected .bubble,
      .lan-bulk-selected .group-bubble {
        outline: 2px solid #5a9cff !important;
        outline-offset: 2px;
      }

      .lan-bulk-selected {
        user-select: none;
      }

      .lan-bulk-toolbar {
        position: fixed;
        z-index: 25000;
        left: 50%;
        bottom: 25px;
        transform: translateX(-50%);
        padding: 10px 13px;
        display: flex;
        align-items: center;
        gap: 9px;
        background: #1e2637;
        color: white;
        border: 1px solid rgba(255,255,255,.18);
        border-radius: 14px;
        box-shadow: 0 12px 40px rgba(0,0,0,.4);
        font-family: inherit;
      }

      .lan-bulk-toolbar span {
        font-size: 12px;
        white-space: nowrap;
        padding-right: 4px;
      }

      .lan-bulk-toolbar button,
      .lan-bulk-dialog button {
        border-radius: 8px;
        padding: 8px 12px;
        font: inherit;
        font-size: 12px;
        cursor: pointer;
        color: white;
        background: #394765;
        border: 1px solid rgba(255,255,255,.16);
      }

      .lan-bulk-toolbar button:hover,
      .lan-bulk-dialog button:hover {
        background: #4b628a;
      }

      .lan-bulk-overlay {
        position: fixed;
        inset: 0;
        z-index: 26000;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(0,0,0,.68);
        padding: 20px;
      }

      .lan-bulk-dialog {
        background: #202a3d;
        color: #f5f7ff;
        border-radius: 16px;
        padding: 23px;
        width: min(390px, 90vw);
        box-shadow: 0 16px 45px rgba(0,0,0,.45);
      }

      .lan-bulk-dialog h3 {
        margin: 0 0 10px;
        font-size: 18px;
      }

      .lan-bulk-dialog p {
        line-height: 1.5;
        opacity: .85;
        font-size: 13px;
      }

      .lan-bulk-dialog-actions,
      .lan-bulk-destinations {
        display: flex;
        flex-direction: column;
        gap: 8px;
        margin-top: 18px;
      }
    `;

    document.head.appendChild(style);
  }

  function startWatching() {
    if (watching || !document.body) return;

    watching = true;

    const observer = new MutationObserver(refreshRows);

    observer.observe(document.body, {
      childList: true,
      subtree: true
    });
  }

  injectStyles();
  installEventHooks();
  startWatching();

  window.LANMultiSelect = {
    clear: clearSelection
  };

})();
