(function () {
  'use strict';

  var STYLE = '' +
    '@keyframes il-shimmer{0%{background-position:-200% 0;}100%{background-position:200% 0;}}' +
    'img[data-il-pending="1"]{background:linear-gradient(90deg,#eee 0%,#f5f5f5 50%,#eee 100%) !important;background-size:200% 100% !important;animation:il-shimmer 1.4s linear infinite;opacity:1 !important;}' +
    'img[data-il-pending="1"]:not([src]){visibility:hidden;}' +
    'img[data-il-fade]{transition:opacity .35s ease;}';

  var SKIP_CLASSES = [
    'mc-arrow', 'mc-circle', 'tp-arrow', 'ring-arrow',
    'frame-arrow-2', 'frame-arrow-3', 'recog-icon', 'recog-thumb',
    'dl-arrow', 'panel-icon', 'm-back', 'illust-reflection'
  ];

  function shouldSkip(img) {
    if (!img || !img.tagName || img.tagName !== 'IMG') return true;
    for (var i = 0; i < SKIP_CLASSES.length; i++) {
      if (img.classList && img.classList.contains(SKIP_CLASSES[i])) return true;
    }
    // Tiny decorative images (< 60px). Avoid skeletoning small icons.
    var w = img.getAttribute('width');
    if (w && parseInt(w, 10) < 60) return true;
    return false;
  }

  function injectStyle() {
    if (document.getElementById('il-style')) return;
    var s = document.createElement('style');
    s.id = 'il-style';
    s.textContent = STYLE;
    document.head.appendChild(s);
  }

  function bindImg(img) {
    if (img.dataset.ilBound) return;
    img.dataset.ilBound = '1';

    if (img.complete && img.naturalWidth > 0) return;

    img.dataset.ilPending = '1';
    img.dataset.ilFade = '1';
    img.style.opacity = '0';

    var done = function () {
      delete img.dataset.ilPending;
      // Force reflow before fade-in.
      // eslint-disable-next-line no-unused-expressions
      img.offsetHeight;
      img.style.opacity = '1';
      img.removeEventListener('load', done);
      img.removeEventListener('error', done);
    };
    img.addEventListener('load', done);
    img.addEventListener('error', done);
  }

  function scan() {
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      if (shouldSkip(imgs[i])) continue;
      bindImg(imgs[i]);
    }
  }

  function init() {
    injectStyle();
    scan();
    var observer = new MutationObserver(function () { scan(); });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
