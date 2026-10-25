
/*
 * LAN Messenger - Group Conversation Clear Module
 * Independent of private chat and group messaging.
 */
(function () {
  'use strict';

  const $ = id => document.getElementById(id);
  const api = () => window.lanGroups;

  let groups = [];
  let dialog = null;

  function toast(message) {
    const node = $('toast');
    if (!node) return;

    node.textContent = String(message);
    node.style.display = 'block';

    setTimeout(() => {
      if (node.textContent === message) {
        node.style.display = 'none';
      }
    }, 3500);
  }

  // Resolve the actual group ID from its sidebar position.
  // This also works when multiple groups have the same name.
  function activeGroup() {
    const rows = Array.from(
      document.querySelectorAll(
        '#group-list .group-contact'
      )
    );

    const index = rows.findIndex(
      row => row.classList.contains('active')
    );

    if (index < 0 || index >= groups.length) {
      return null;
    }

    return groups[index];
  }

  function closeDialog() {
    if (dialog) {
      dialog.remove();
      dialog = null;
    }
  }

  function confirmClear(group) {
    closeDialog();

    dialog = document.createElement('div');
    dialog.className = 'lan-gclear-overlay';

    const panel = document.createElement('div');
    panel.className = 'lan-gclear-panel';

    const heading = document.createElement('h3');
    heading.textContent = 'Clear group conversation?';

    const description = document.createElement('p');
    description.textContent =
      'Delete all messages and file cards in "' +
      group.name +
      '" from this computer? This will not delete ' +
      'messages from other members or remove the group.';

    const actions = document.createElement('div');
    actions.className = 'lan-gclear-actions';

    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.textContent = 'Cancel';
    cancel.onclick = closeDialog;

    const clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'lan-gclear-danger';
    clear.textContent = 'Clear Conversation';

    clear.onclick = () => {
      const bridge = api();

      if (!bridge) {
        toast('Group service unavailable');
        return;
      }

      clear.disabled = true;
      clear.textContent = 'Clearing...';

      try {
        bridge.clearGroupConversation(group.id);

        // Wait for Java's conversationCleared event
        // before reporting a successful deletion.
      } catch (error) {
        clear.disabled = false;
        clear.textContent = 'Clear Conversation';
        toast('Could not clear conversation');
        console.error(error);
      }
    };

    actions.append(cancel, clear);
    panel.append(heading, description, actions);
    dialog.appendChild(panel);

    dialog.onclick = e => {
      if (e.target === dialog) closeDialog();
    };

    document.body.appendChild(dialog);
  }

  // Add to the EXISTING group Manage dialog.
  function addClearButton() {
    const body = $('group-modal-body');
    const group = activeGroup();

    if (!body || !group) return;

    if (body.querySelector('#lan-clear-group')) {
      return;
    }

    const section = document.createElement('div');
    section.className = 'group-section-label';
    section.textContent = 'Conversation';

    const button = document.createElement('button');
    button.id = 'lan-clear-group';
    button.type = 'button';
    button.className = 'group-action danger';
    button.textContent = 'Clear Conversation';

    button.onclick = () => {
      const modal = $('group-modal');

      if (modal) {
        modal.classList.add('hidden');
      }

      confirmClear(group);
    };

    body.append(section, button);
  }

  const manage = $('group-manage');

  if (manage) {
    manage.addEventListener('click', () => {
      // Existing groups.js opens the Manage dialog first.
      setTimeout(addClearButton, 0);
    });
  }

  // Listen to existing group bridge events.
  // Preserve its original receiver and all other features.
  if (window.LANGroups &&
      typeof window.LANGroups.receive === 'function') {

    const originalReceive = window.LANGroups.receive;

    window.LANGroups.receive = function (type, payload) {

      if (type === 'groups' && Array.isArray(payload)) {
        groups = payload.slice();
      }

      const result = originalReceive.call(
        window.LANGroups,
        type,
        payload
      );

      if (type === 'conversationCleared') {
        closeDialog();

        if (payload && payload.groupId) {
          toast(
            'Group conversation cleared on this computer'
          );
        }
      }

      if (type === 'error' && dialog) {
        closeDialog();
      }

      return result;
    };
  }

  const style = document.createElement('style');
  style.id = 'lan-group-clear-styles';

  style.textContent = `
    .lan-gclear-overlay {
      position: fixed;
      inset: 0;
      z-index: 30000;
      display: flex;
      justify-content: center;
      align-items: center;
      background: rgba(0,0,0,.72);
      padding: 20px;
    }

    .lan-gclear-panel {
      box-sizing: border-box;
      width: min(420px,95vw);
      padding: 25px;
      border-radius: 17px;
      background: #20293b;
      color: #f5f7ff;
      box-shadow: 0 16px 50px rgba(0,0,0,.4);
      font-family: inherit;
    }

    .lan-gclear-panel h3 {
      font-size: 19px;
      margin: 0 0 14px;
    }

    .lan-gclear-panel p {
      font-size: 13px;
      line-height: 1.65;
      opacity: .85;
    }

    .lan-gclear-actions {
      display: flex;
      justify-content: flex-end;
      flex-wrap: wrap;
      gap: 10px;
      margin-top: 24px;
    }

    .lan-gclear-actions button {
      padding: 10px 14px;
      border-radius: 9px;
      border: 1px solid rgba(255,255,255,.18);
      background: #39445b;
      color: white;
      cursor: pointer;
      font: inherit;
      font-size: 12px;
    }

    .lan-gclear-actions .lan-gclear-danger {
      background: #c83e4b;
      border-color: #c83e4b;
    }

    .lan-gclear-actions button:disabled {
      opacity: .55;
      cursor: default;
    }
  `;

  document.head.appendChild(style);

})();
