
(function () {
  'use strict';

  const EMOJIS = [
    '😀','😃','😄','😁','😆','😅','😂','🤣','😊',
    '😍','🥰','😘','😗','😙','😚','😋','😛','😜',
    '🤪','😝','🤗','🤔','😐','🙄','😏','😴','😭',
    '😢','😡','😠','😎','🥳','🤩','🥹','🥺','😇',
    '🤭','🫶','😷','💀','👀','👍','👎','👏',
    '🙌','🙏','💪','👋','🤝','❤️','🧡','💛',
    '💚','💙','💜','🖤','🤍','💔','💕','💖',
    '💯','🔥','✨','🎉','🎂','🌹','🌸','⭐'
  ];

  const menu = document.getElementById('emoji-menu');
  const input = document.getElementById('message-input');
  const messages = document.getElementById('messages');

  if (!menu || !input || !messages) return;

  const sorted = [...EMOJIS].sort(
    (a, b) => b.length - a.length
  );

  const slugs = new Map(
    EMOJIS.map(e => [
      e,
      [...e]
        .filter(c => c.codePointAt(0) !== 0xfe0f)
        .map(c => c.codePointAt(0).toString(16))
        .join('-')
    ])
  );

  // Enter inserts a newline.
  // The existing app.js Enter-to-send handler is bypassed.
  // Default browser textarea behavior remains active.

  window.addEventListener('keydown', function (event) {
    if (
      event.target === input &&
      event.key === 'Enter'
    ) {
      event.stopPropagation();
    }
  }, true);

  // Build the image-based emoji picker.
  menu.replaceChildren();

  EMOJIS.forEach(emoji => {
    const button = document.createElement('button');
    button.type = 'button';
    button.title = emoji;
    button.setAttribute('aria-label', 'Insert emoji');

    const image = document.createElement('img');
    image.src = 'emoji/' + slugs.get(emoji) + '.svg';
    image.alt = emoji;
    image.draggable = false;

    button.appendChild(image);

    button.addEventListener('click', function () {
      if (!input.disabled) {
        const start = input.selectionStart;
        const end = input.selectionEnd;

        input.setRangeText(
          emoji,
          start,
          end,
          'end'
        );

        input.focus();
        input.dispatchEvent(
          new Event('input', { bubbles: true })
        );
      }

      menu.classList.add('hidden');
    });

    menu.appendChild(button);
  });

  function escapeRegex(value) {
    return value.replace(
      /[.*+?^${}()|[\]\\]/g,
      '\\$&'
    );
  }

  const expression = new RegExp(
    sorted.map(escapeRegex).join('|'),
    'gu'
  );

  function displayEmoji(textNode) {
    const value = textNode.nodeValue;

    expression.lastIndex = 0;

    if (!expression.test(value)) return;

    expression.lastIndex = 0;

    const fragment = document.createDocumentFragment();

    let last = 0;
    let match;

    while ((match = expression.exec(value))) {
      if (match.index > last) {
        fragment.appendChild(
          document.createTextNode(
            value.slice(last, match.index)
          )
        );
      }

      const image = document.createElement('img');
      image.className = 'emoji-image';
      image.src = 'emoji/' + slugs.get(match[0]) + '.svg';
      image.alt = match[0];
      image.title = match[0];
      image.draggable = false;

      fragment.appendChild(image);
      last = match.index + match[0].length;
    }

    if (last < value.length) {
      fragment.appendChild(
        document.createTextNode(value.slice(last))
      );
    }

    textNode.parentNode.replaceChild(
      fragment,
      textNode
    );
  }

  function decorateChats() {
    const bubbles = messages.querySelectorAll(
      '.bubble-text:not([data-emoji-ready])'
    );

    bubbles.forEach(element => {
      element.dataset.emojiReady = 'true';

      [...element.childNodes]
        .filter(node => node.nodeType === Node.TEXT_NODE)
        .forEach(displayEmoji);
    });
  }

  // Renders emojis automatically when an incoming message
  // appears without refreshing the conversation.

  const observer = new MutationObserver(decorateChats);

  observer.observe(messages, {
    childList: true,
    subtree: true
  });

  decorateChats();
})();
