
/*
 * LAN Messenger - Profile Photo Viewer
 *
 * Supports:
 * - Private chat profile pictures
 * - Sidebar contact pictures
 * - Group pictures
 * - Own profile picture
 * - JavaFX WebView
 *
 * Independent module. No Java changes required.
 */

(function () {
  'use strict';

  const AVATAR_SELECTOR = [
    '.group-avatar',
    '.contact-avatar',
    '.chat-avatar',
    '.profile-avatar',
    '.user-avatar',
    '.contact-photo',
    '.profile-photo',
    '.avatar',
    '#profile-avatar',
    '#contact-avatar',
    '.conversation header img',
    '.sidebar .contact img',
    '.group-header img'
  ].join(',');

  let overlay = null;
  let previousFocus = null;

  function findPhoto(avatar) {
    if (!avatar) return null;

    const img = avatar.tagName === 'IMG'
      ? avatar
      : avatar.querySelector('img');

    if (img) {
      const source =
        img.currentSrc ||
        img.getAttribute('src');

      if (source && source.trim()) {
        return source.trim();
      }
    }

    // Handles the group avatar images currently
    // stored as CSS background-image URLs.
    const background = window.getComputedStyle(
      avatar
    ).backgroundImage;

    if (
      background &&
      background !== 'none'
    ) {
      const match = background.match(
        /url\(\s*(["']?)(.*?)\1\s*\)/i
      );

      if (match && match[2]) {
        return match[2];
      }
    }

    return null;
  }

  function allowedPhoto(url) {
    if (!url || typeof url !== 'string') {
      return false;
    }

    return /^(data:image\/(?:png|jpeg|gif|webp|bmp);base64,|https?:|file:|jar:|resource:|\.?\.?\/)/i
      .test(url);
  }

  function findName(avatar) {
    const container = avatar.closest(
      '.group-contact, ' +
      '.group-header, ' +
      '.contact, ' +
      '.contact-item, ' +
      '.chat-header, ' +
      '.conversation-header, ' +
      '.profile-card, ' +
      '.profile'
    );

    if (!container) {
      return avatar.getAttribute('alt') ||
        avatar.getAttribute('title') ||
        'Profile photo';
    }

    const name = container.querySelector(
      '#group-name, ' +
      '.contact-name, ' +
      '.chat-name, ' +
      '.profile-name, ' +
      '.group-header-copy strong, ' +
      '.contact-info strong, ' +
      'strong'
    );

    if (name && name.textContent.trim()) {
      return name.textContent.trim();
    }

    return avatar.getAttribute('alt') ||
      'Profile photo';
  }

  function closeViewer() {
    if (!overlay) return;

    overlay.remove();
    overlay = null;

    document.body.classList.remove(
      'lan-photo-viewing'
    );

    if (
      previousFocus &&
      document.contains(previousFocus)
    ) {
      try {
        previousFocus.focus();
      } catch (error) {
        // Ignore unavailable focus targets.
      }
    }

    previousFocus = null;
  }

  function openViewer(source, name) {
    closeViewer();

    previousFocus = document.activeElement;

    overlay = document.createElement('div');
    overlay.className = 'lan-photo-overlay';
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-modal', 'true');
    overlay.setAttribute(
      'aria-label',
      'Profile photo viewer'
    );

    const container = document.createElement('div');
    container.className = 'lan-photo-container';

    const closeButton = document.createElement('button');
    closeButton.className = 'lan-photo-close';
    closeButton.type = 'button';
    closeButton.textContent = '×';
    closeButton.title = 'Close photo';
    closeButton.setAttribute(
      'aria-label',
      'Close profile photo'
    );

    closeButton.onclick = closeViewer;

    const imageBox = document.createElement('div');
    imageBox.className = 'lan-photo-image-box';

    const image = document.createElement('img');
    image.className = 'lan-photo-large';
    image.alt = name || 'Profile photo';
    image.draggable = false;

    const loading = document.createElement('div');
    loading.className = 'lan-photo-loading';
    loading.textContent = 'Loading photo...';

    image.style.display = 'none';

    image.onload = function () {
      loading.remove();
      image.style.display = 'block';
    };

    image.onerror = function () {
      image.style.display = 'none';
      loading.textContent =
        'Could not load this profile photo.';
    };

    image.src = source;

    imageBox.append(loading, image);

    const caption = document.createElement('div');
    caption.className = 'lan-photo-caption';
    caption.textContent = name || 'Profile photo';

    container.append(
      closeButton,
      imageBox,
      caption
    );

    overlay.append(container);
    document.body.appendChild(overlay);

    document.body.classList.add(
      'lan-photo-viewing'
    );

    overlay.addEventListener('click', function (e) {
      if (e.target === overlay) {
        closeViewer();
      }
    });

    closeButton.focus();
  }

  // Event delegation supports avatars added
  // dynamically after the user opens a group.
  document.addEventListener('click', function (event) {
    if (overlay) return;

    const target = event.target;

    if (!target || !target.closest) {
      return;
    }

    const avatar = target.closest(
      AVATAR_SELECTOR
    );

    if (!avatar) return;

    const source = findPhoto(avatar);

    // Initial-letter avatars are not photos.
    // Preserve their normal chat-opening behavior.
    if (!allowedPhoto(source)) {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();

    openViewer(
      source,
      findName(avatar)
    );

  }, true);

  document.addEventListener('keydown', function (event) {
    if (!overlay) return;

    if (event.key === 'Escape') {
      event.preventDefault();
      closeViewer();
    }

    // Keep keyboard focus inside the viewer.
    if (event.key === 'Tab') {
      event.preventDefault();

      const button = overlay.querySelector(
        '.lan-photo-close'
      );

      if (button) button.focus();
    }
  });

  const style = document.createElement('style');
  style.id = 'lan-profile-photo-viewer-style';

  style.textContent = `
    .lan-photo-overlay {
      position: fixed;
      inset: 0;
      z-index: 99999;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 22px;
      box-sizing: border-box;
      background: rgba(5, 8, 16, 0.93);
      backdrop-filter: blur(12px);
      -webkit-backdrop-filter: blur(12px);
      animation: lanPhotoFade .18s ease-out;
    }

    .lan-photo-container {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 18px;
      width: min(520px, 94vw);
      max-height: 94vh;
      padding: 35px 25px 20px;
      box-sizing: border-box;
      border-radius: 20px;
      background: rgba(255, 255, 255, 0.055);
      border: 1px solid rgba(255, 255, 255, 0.12);
      box-shadow: 0 20px 70px rgba(0, 0, 0, 0.5);
    }

    .lan-photo-close {
      position: absolute;
      top: 9px;
      right: 13px;
      width: 36px;
      height: 36px;
      border: none;
      border-radius: 50%;
      background: rgba(255, 255, 255, 0.12);
      color: white;
      font-size: 27px;
      line-height: 1;
      cursor: pointer;
      z-index: 1;
    }

    .lan-photo-close:hover {
      background: rgba(255, 255, 255, 0.25);
    }

    .lan-photo-image-box {
      width: min(390px, 75vw);
      height: min(390px, 60vh);
      display: flex;
      align-items: center;
      justify-content: center;
      overflow: hidden;
      border-radius: 50%;
      border: 4px solid rgba(255, 255, 255, 0.18);
      background: rgba(255, 255, 255, 0.06);
      box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
    }

    .lan-photo-large {
      display: block;
      width: 100%;
      height: 100%;
      object-fit: cover;
      border-radius: 50%;
      user-select: none;
      -webkit-user-select: none;
    }

    .lan-photo-caption {
      color: #ffffff;
      font-family: inherit;
      font-size: 17px;
      font-weight: 600;
      text-align: center;
      max-width: 100%;
      overflow-wrap: anywhere;
    }

    .lan-photo-loading {
      color: #d1d5db;
      font-size: 13px;
      text-align: center;
      padding: 12px;
    }

    .group-avatar[style*="url("],
    .contact-avatar[style*="url("],
    .chat-avatar[style*="url("],
    .profile-avatar[style*="url("],
    .avatar img,
    .contact-avatar img,
    .group-avatar img,
    .conversation header img {
      cursor: zoom-in;
    }

    @keyframes lanPhotoFade {
      from {
        opacity: 0;
      }
      to {
        opacity: 1;
      }
    }
  `;

  document.head.appendChild(style);

  window.LANProfileViewer = Object.freeze({
    open: function (source, name) {
      if (allowedPhoto(source)) {
        openViewer(source, name);
      }
    },
    close: closeViewer
  });

})();
