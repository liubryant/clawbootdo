(function () {
  'use strict';

  var STYLE = '' +
    '.lb-overlay{position:fixed;inset:0;background:rgba(0,0,0,0.85);display:flex;align-items:center;justify-content:center;z-index:999999;opacity:0;pointer-events:none;transition:opacity .2s ease;-webkit-tap-highlight-color:transparent;}' +
    '.lb-overlay.active{opacity:1;pointer-events:auto;}' +
    '.lb-overlay .lb-stage{position:relative;width:min(92vw,1400px);height:min(88vh,900px);display:flex;align-items:center;justify-content:center;}' +
    '.lb-overlay .lb-media{max-width:100%;max-height:100%;object-fit:contain;border-radius:8px;box-shadow:0 12px 48px rgba(0,0,0,0.5);}' +
    '.lb-overlay video.lb-media{width:auto;height:auto;background:#000;}' +
    '.lb-overlay img.lb-media{background:#fff;}' +
    '.lb-overlay .lb-group-clone{position:relative;background:#fff;border-radius:8px;box-shadow:0 12px 48px rgba(0,0,0,0.5);transform-origin:center center;}' +
    '.lb-overlay .lb-close{position:absolute;top:-44px;right:0;width:36px;height:36px;border-radius:50%;border:1px solid rgba(255,255,255,0.3);background:rgba(0,0,0,0.5);color:#fff;font-size:20px;line-height:1;cursor:pointer;display:flex;align-items:center;justify-content:center;padding:0;-webkit-tap-highlight-color:transparent;}' +
    '.lb-overlay .lb-close:hover{background:rgba(255,255,255,0.15);}' +
    '@media (max-width:480px){.lb-overlay .lb-close{top:auto;bottom:-48px;right:50%;transform:translateX(50%);}}' +
    '[data-lb-zoomable]{cursor:zoom-in;}';

  function injectStyle() {
    if (document.getElementById('lb-style')) return;
    var s = document.createElement('style');
    s.id = 'lb-style';
    s.textContent = STYLE;
    document.head.appendChild(s);
  }

  var overlay, mediaSlot, closeBtn, currentMedia;

  function createOverlay() {
    if (overlay) return;
    overlay = document.createElement('div');
    overlay.className = 'lb-overlay';
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-modal', 'true');
    overlay.innerHTML = '<div class="lb-stage"><button type="button" class="lb-close" aria-label="关闭">×</button></div>';
    document.body.appendChild(overlay);

    mediaSlot = overlay.querySelector('.lb-stage');
    closeBtn = overlay.querySelector('.lb-close');

    overlay.addEventListener('click', function (e) {
      if (e.target === overlay || e.target === mediaSlot) close();
    });
    closeBtn.addEventListener('click', close);
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && overlay.classList.contains('active')) close();
    });
  }

  function openGroup(sourceEl) {
    createOverlay();
    if (currentMedia) currentMedia.remove();

    var rect = sourceEl.getBoundingClientRect();
    var clone = sourceEl.cloneNode(true);
    clone.removeAttribute('id');
    clone.classList.add('lb-group-clone');
    clone.style.width = rect.width + 'px';
    clone.style.height = rect.height + 'px';
    clone.style.cursor = 'zoom-out';

    var stageRect = mediaSlot.getBoundingClientRect();
    var sw = (window.innerWidth * 0.92) || stageRect.width;
    var sh = (window.innerHeight * 0.88) || stageRect.height;
    var scale = Math.min(sw / rect.width, sh / rect.height, 3);
    if (!isFinite(scale) || scale <= 0) scale = 1;
    clone.style.transform = 'scale(' + scale + ')';

    currentMedia = clone;
    mediaSlot.insertBefore(clone, closeBtn);
    overlay.classList.add('active');
    document.documentElement.style.overflow = 'hidden';
  }

  function open(src, type, poster) {
    createOverlay();
    if (currentMedia) currentMedia.remove();

    if (type === 'video') {
      currentMedia = document.createElement('video');
      currentMedia.src = src;
      currentMedia.autoplay = true;
      currentMedia.controls = true;
      currentMedia.loop = true;
      currentMedia.muted = true;
      currentMedia.setAttribute('muted', '');
      currentMedia.playsInline = true;
      currentMedia.setAttribute('playsinline', '');
      if (poster) currentMedia.poster = poster;
    } else {
      currentMedia = document.createElement('img');
      currentMedia.src = src;
      currentMedia.alt = '';
    }
    currentMedia.className = 'lb-media';
    mediaSlot.insertBefore(currentMedia, closeBtn);
    overlay.classList.add('active');
    document.documentElement.style.overflow = 'hidden';
  }

  function close() {
    if (!overlay) return;
    overlay.classList.remove('active');
    document.documentElement.style.overflow = '';
    if (currentMedia) {
      if (currentMedia.tagName === 'VIDEO') {
        try { currentMedia.pause(); } catch (e) {}
      }
      setTimeout(function () {
        if (currentMedia) { currentMedia.remove(); currentMedia = null; }
      }, 200);
    }
  }

  // CSS selectors of clickable media. Skip controller arrows / icons / decorative overlays.
  var SKIP_CLASSES = [
    'mc-arrow', 'mc-circle', 'tp-arrow', 'ring-arrow',
    'frame-arrow-2', 'frame-arrow-3', 'recog-icon', 'recog-thumb',
    'dl-arrow', 'lb-media',
    'rc-img', 'task-img', 'bottom-overlay',
    'panel-icon', 'm-back'
  ];

  function shouldSkip(el) {
    for (var i = 0; i < SKIP_CLASSES.length; i++) {
      if (el.classList && el.classList.contains(SKIP_CLASSES[i])) return true;
    }
    // hero-img: skip when it is an <img> (stage 4 still images),
    // keep clickable when it is a <video> (stage 5 demo).
    if (el.classList && el.classList.contains('hero-img') && el.tagName === 'IMG') return true;
    // hero <img> directly inside .stage-4 .hero on desktop
    if (el.tagName === 'IMG' && el.parentElement && el.parentElement.classList &&
        el.parentElement.classList.contains('hero') &&
        el.closest && el.closest('.stage-4')) return true;
    // Desktop (>480px): disable zoom for all images, keep videos clickable.
    var isDesktop = (typeof window !== 'undefined') && window.matchMedia &&
                    window.matchMedia('(min-width: 481px)').matches;
    if (isDesktop && el.tagName === 'IMG') return true;
    return false;
  }

  function bindAll() {
    var groups = document.querySelectorAll('[data-lb-group]');
    for (var g = 0; g < groups.length; g++) {
      var gEl = groups[g];
      if (gEl.dataset.lbBound) continue;
      gEl.dataset.lbBound = '1';
      gEl.dataset.lbZoomable = '1';
      gEl.style.cursor = 'zoom-in';
      gEl.addEventListener('click', onGroupClick);
    }

    var nodes = document.querySelectorAll('img, video');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (shouldSkip(el)) continue;
      if (el.closest && el.closest('[data-lb-group]')) continue;
      if (el.dataset.lbBound) continue;
      el.dataset.lbBound = '1';
      el.dataset.lbZoomable = '1';
      el.style.cursor = 'zoom-in';
      // ensure clickable even if author CSS sets pointer-events:none
      if (getComputedStyle(el).pointerEvents === 'none') {
        el.style.pointerEvents = 'auto';
      }
      el.addEventListener('click', onClick);
    }
  }

  function onGroupClick(e) {
    var el = e.currentTarget;
    e.preventDefault();
    e.stopPropagation();
    openGroup(el);
  }

  function onClick(e) {
    var el = e.currentTarget;
    e.preventDefault();
    e.stopPropagation();
    if (el.tagName === 'VIDEO') {
      open(el.currentSrc || el.src, 'video', el.poster);
    } else {
      open(el.currentSrc || el.src, 'image');
    }
  }

  function init() {
    injectStyle();
    bindAll();
    // Watch for dynamically toggled stages.
    var observer = new MutationObserver(function () { bindAll(); });
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'] });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
