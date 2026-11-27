/* Yumehub Android 适配；官方 markdown.js 保持原始字节不变。 */
(() => {
  'use strict';
  if (window.top !== window || window.androidMarkdown) return;
  let currentId;
  let lastHeight;
  let pendingFrame = 0;
  let installed = false;
  let previousRender;
  let active = true;
  let viewport = false;
  let nativeCode = [];
  let codeLayoutSignature;
  let layoutReported = false;
  const pauseMedia = () => document.querySelectorAll('audio,video').forEach(media => media.pause());
  const send = (name, fields) => window.native.sendMessage(JSON.stringify({
    ...fields, id: currentId, messageName: name
  }));
  const measure = () => {
    // 不使用带视口下限的 body.scrollHeight，保证 details 收起后高度也能缩小。
    const content = document.getElementById('content');
    const bottom = content.getBoundingClientRect().bottom + window.scrollY;
    const height = Math.max(1, Math.ceil(bottom));
    if (height !== lastHeight) {
      lastHeight = height;
      send('height', {height});
    }
  };
  // 媒体/ResizeObserver/字体在同一帧只触发一次布局读取。
  const scheduleMeasure = () => {
    if (!currentId || pendingFrame) return;
    pendingFrame = window.requestAnimationFrame(() => {
      pendingFrame = 0;
      if (!viewport) measure();
      reportCodeLayout();
      if (!layoutReported) {
        layoutReported = true;
        send('layout', {});
      }
    });
  };
  // pre 再次解析会吞掉首个换行；序列化副本前补回，保留原代码的空行。
  const preservePreNewline = pre => {
    if (pre.firstChild?.nodeType === Node.TEXT_NODE && pre.firstChild.data.startsWith('\n')) {
      pre.insertBefore(document.createTextNode('\n'), pre.firstChild);
    }
  };
  const reportCodeSources = () => send('code_blocks', {
    blocks: nativeCode.map(({id, html}) => ({id, html}))
  });
  const installNativeCode = enabled => {
    nativeCode = [];
    codeLayoutSignature = undefined;
    if (!enabled) return;
    const content = document.getElementById('content');
    for (const pre of content.querySelectorAll('pre')) {
      // 可应用的建议块保留其行号、增删标记和保存动作，不当作普通 fenced code。
      if (pre.closest('.js-suggested-changes-blob,.js-apply-changes,math-renderer,math,.mermaid')) continue;
      const id = nativeCode.length;
      // 保留官方高亮 span 和原始空白，交给同一 HTML 原生库，不重做文本语法高亮。
      const fragment = pre.cloneNode(true);
      preservePreNewline(fragment);
      const html = fragment.outerHTML;
      const initialBounds = pre.getBoundingClientRect();
      const initialHeight = Math.max(1, initialBounds.height);
      const intrinsicWidth = pre.closest('table') ? initialBounds.width : null;
      nativeCode.push({id, html, element:pre, intrinsicWidth});
      // 只清空代码内容；图片、SVG、表格及它们的原始布局上下文不拆分、不重新序列化。
      pre.replaceChildren();
      pre.classList.add('android-native-code');
      pre.style.setProperty('height', initialHeight + 'px', 'important');
      pre.closest('.highlight')?.querySelectorAll('clipboard-copy,.zeroclipboard-container')
        .forEach(element => element.remove());
    }
    reportCodeSources();
  };
  const reportCodeLayout = () => {
    if (!nativeCode.length) return;
    const layout = nativeCode.map(({id, element, intrinsicWidth}) => {
      // 自动列宽表格失去 HTML 文本后会缩为零；保留原代码的固有宽度，仍受当前视口限制。
      if (intrinsicWidth != null) element.style.setProperty('width',
        Math.min(intrinsicWidth, document.getElementById('content').clientWidth) + 'px', 'important');
      const rect = element.getBoundingClientRect();
      let visible = element.getClientRects().length > 0 &&
        (!element.checkVisibility || element.checkVisibility({checkVisibilityCSS:true}));
      for (let parent = element.parentElement; visible && parent; parent = parent.parentElement) {
        if (parent.tagName === 'DETAILS' && !parent.open &&
            !parent.querySelector(':scope > summary')?.contains(element)) visible = false;
      }
      if (!visible) return [id,0,0,0,0,0,0,0,0];
      let left = rect.left, top = rect.top, right = rect.right, bottom = rect.top + 1000000;
      for (let parent = element.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
        const style = window.getComputedStyle(parent);
        const bounds = parent.getBoundingClientRect();
        if (/^(auto|scroll|hidden|clip)$/.test(style.overflowX)) {
          left = Math.max(left, bounds.left + parent.clientLeft);
          right = Math.min(right, bounds.left + parent.clientLeft + parent.clientWidth);
        }
        if (/^(auto|scroll|hidden|clip)$/.test(style.overflowY)) {
          top = Math.max(top, bounds.top + parent.clientTop);
          bottom = Math.min(bottom, bounds.top + parent.clientTop + parent.clientHeight);
        }
      }
      return [id, rect.left + window.scrollX, rect.top + window.scrollY, rect.width, rect.height,
        left - rect.left, top - rect.top, Math.max(left,right) - rect.left, Math.max(top,bottom) - rect.top];
    });
    const signature = JSON.stringify(layout);
    if (signature === codeLayoutSignature) return;
    codeLayoutSignature = signature;
    send('code_layout', {layout});
  };
  const install = () => {
    if (installed) return;
    installed = true;
    window.github._updateHeight = scheduleMeasure;
    // 保留官方资源，宿主统一接管高度，避免每次 github.load 重复观察 body。
    window.github._addHeightListener = () => {};
    const style = document.createElement('style');
    style.textContent = 'html,body{margin:0;padding:0;min-height:0;height:auto;overflow-x:hidden;overflow-anchor:none;}'
      + '#content{display:flow-root;overflow-wrap:anywhere;}'
      + '#content img,#content video,#content svg{max-width:100%;}'
      + '#content pre,#content table{max-width:100%;overflow-x:auto;overscroll-behavior-x:contain;}'
      + '#content pre,#content pre *,#content table,#content table *{touch-action:pan-x pan-y;}'
      + '#content table{display:block;}';
    style.textContent += '#content pre.android-native-code{display:flow-root;box-sizing:border-box;'
      + 'padding:0!important;border:0!important;background:transparent!important;overflow:visible!important;}';
    document.head.appendChild(style);
    if (typeof ResizeObserver !== 'undefined') {
      new ResizeObserver(scheduleMeasure).observe(document.getElementById('content'));
    }
    for (const event of ['load', 'error', 'toggle', 'resize']) {
      document.addEventListener(event, scheduleMeasure, true);
    }
    if (window.addEventListener) window.addEventListener('resize', scheduleMeasure);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(scheduleMeasure);
  };
  const anchor = raw => {
    const name = String(raw).replace(/^#/, '');
    if (!name) {
      send('scroll_to', {posY: 0});
      return;
    }
    // 复用官方的一次解码、大小写与 user-content 前缀匹配、零矩形父节点回退。
    const position = window.github._positionOf(name);
    if (typeof position === 'number' && Number.isFinite(position)) {
      send('scroll_to', {posY: position + window.scrollY});
    }
  };
  let touchTarget = null;
  let lastHorizontalScroll;
  const reportHorizontalScroll = () => {
    let canScrollLeft = false;
    let canScrollRight = false;
    for (let element = touchTarget; element && element !== document.body; element = element.parentElement) {
      const extent = element.scrollWidth - element.clientWidth;
      if (!(extent > 1) || !/^(auto|scroll)$/.test(window.getComputedStyle(element).overflowX)) continue;
      const offset = element.scrollLeft;
      // RTL scrollLeft 为负；以实际滚动坐标判断两端，而不是对所有横向拖动一律拦截。
      if (window.getComputedStyle(element).direction === 'rtl') {
        canScrollLeft ||= offset > -extent + 1;
        canScrollRight ||= offset < -1;
      } else {
        canScrollLeft ||= offset > 1;
        canScrollRight ||= offset < extent - 1;
      }
    }
    const signature = `${canScrollLeft}:${canScrollRight}`;
    if (currentId && signature !== lastHorizontalScroll) {
      lastHorizontalScroll = signature;
      send('horizontal_scroll', {canScrollLeft, canScrollRight});
    }
  };
  document.addEventListener('touchstart', event => {
    touchTarget = event.target;
    lastHorizontalScroll = undefined;
    reportHorizontalScroll();
  }, {capture:true, passive:true});
  document.addEventListener('touchmove', reportHorizontalScroll, {capture:true, passive:true});
  document.addEventListener('scroll', () => {
    if (touchTarget) reportHorizontalScroll();
    scheduleMeasure();
  }, {capture:true, passive:true});
  for (const event of ['touchend', 'touchcancel']) {
    document.addEventListener(event, () => {
      touchTarget = null;
      reportHorizontalScroll();
    }, {capture:true, passive:true});
  }
  window.androidMarkdown = {
    // Reattach the existing DOM under a new bridge generation. Media may have resized while parked.
    rebind(id) {
      if (!previousRender) return false;
      currentId = window.github._id = id;
      lastHeight = undefined;
      scheduleMeasure();
      return true;
    },
    setViewport(value) {
      if (viewport === value) return;
      viewport = value;
      if (pendingFrame) {
        window.cancelAnimationFrame(pendingFrame);
        pendingFrame = 0;
      }
      scheduleMeasure();
    },
    setActive(value) {
      active = value;
      if (!active) pauseMedia();
    },
    // 视口内滚动的预览把宿主顶栏/底栏留白交给正文 padding，CSS px 与 dp 一致。
    setInsets(top, right, bottom, left) {
      let style = document.getElementById('android-insets');
      if (!style) {
        style = document.createElement('style');
        style.id = 'android-insets';
        document.head.appendChild(style);
      }
      const px = value => Math.max(0, Number(value) || 0) + 'px';
      style.textContent = 'body{box-sizing:border-box;padding:' + [top, right, bottom, left].map(px).join(' ')
        + ' !important;}';
    },
    load(
      id, html, suggestions, tasks, pageUrl, repositoryRootUrl,
      transparentBackground = false, readme = false, viewerLogin = null,
      nativeCodeBlocks = false, compactBodyTypography = false
    ) {
      currentId = id;
      lastHeight = undefined;
      layoutReported = false;
      install();
      // 无卡片正文透出宿主底色；复用 WebView 时恢复其他页面的官方背景。
      for (const element of [document.documentElement, document.body]) {
        if (transparentBackground) element.style.setProperty('background-color', 'transparent', 'important');
        else element.style.removeProperty('background-color');
      }
      const body = document.getElementById('content');
      if (compactBodyTypography) {
        body.style.setProperty('font-size', '14px');
        body.style.setProperty('font-weight', '400');
      } else {
        body.style.removeProperty('font-size');
        body.style.removeProperty('font-weight');
      }
      const render = {
        html, suggestions, tasks, pageUrl, repositoryRootUrl,
        readme, viewerLogin, nativeCodeBlocks, compactBodyTypography
      };
      if (previousRender && Object.keys(render).every(key => render[key] === previousRender[key])) {
        // HTML 不变时仅换消息代际，保留已有图片、选择与 DOM。
        window.github._id = id;
        if (nativeCodeBlocks) {
          codeLayoutSignature = undefined;
          reportCodeSources();
        }
        scheduleMeasure();
        return;
      }
      previousRender = render;
      // 在惰性 DOM 中做安全准备，渲染仍调用未修改的官方 github.load。
      // HTML 接入文档和原生桥之前移除主动内容。
      const fragment = document.createElement('template');
      fragment.innerHTML = html;
      fragment.content.querySelectorAll(
        'script,iframe,frame,frameset,object,embed,base,meta,link,style,form,template'
      ).forEach(element => element.remove());
      const base = pageUrl || (repositoryRootUrl && repositoryRootUrl.replace(/\/$/, '') + '/');
      const mediaURL = value => {
        try {
          let mediaBase = base;
          if (readme && repositoryRootUrl && pageUrl === repositoryRootUrl.replace(/\/$/, '') + '/') {
            const root = new URL(repositoryRootUrl);
            const parts = root.pathname.split('/').filter(Boolean);
            if (root.hostname === 'github.com' && parts.length === 2) {
              mediaBase = `https://raw.githubusercontent.com/${parts[0]}/${parts[1]}/HEAD/`;
            }
          }
          const mediaValue = mediaBase !== base && value.startsWith('/') && !value.startsWith('//')
            ? value.slice(1) : value;
          const url = new URL(mediaValue, mediaBase || undefined);
          return url.protocol === 'https:' && !url.username && !url.password
            && url.hostname !== 'appassets.androidplatform.net' ? url.href : null;
        } catch (_) { return null; }
      };
      fragment.content.querySelectorAll('*').forEach(element => {
        for (const attribute of Array.from(element.attributes)) {
          const name = attribute.name.toLowerCase();
          if (name.startsWith('on') || ['srcdoc', 'action', 'formaction', 'ping', 'autofocus'].includes(name)) {
            element.removeAttribute(attribute.name);
          }
        }
        for (const name of ['src', 'poster']) {
          if (!element.hasAttribute(name)) continue;
          const url = mediaURL(element.getAttribute(name));
          if (url) element.setAttribute(name, url);
          else element.removeAttribute(name);
        }
        if (element.hasAttribute('srcset')) {
          const candidates = element.getAttribute('srcset').split(',').map(candidate => {
            const parts = candidate.trim().split(/\s+/);
            const url = mediaURL(parts.shift());
            return url ? [url, ...parts].join(' ') : '';
          }).filter(Boolean);
          if (candidates.length) element.setAttribute('srcset', candidates.join(', '));
          else element.removeAttribute('srcset');
        }
        if (element.tagName.toLowerCase() === 'button') element.disabled = true;
      });
      fragment.content.querySelectorAll('.user-mention').forEach(mention => {
        mention.classList.remove('user-mention-viewer');
        if (!viewerLogin) return;
        try {
          const target = new URL(mention.getAttribute('href'), base || undefined);
          const host = base ? new URL(base).hostname : 'github.com';
          if (target.hostname === host &&
              target.pathname.replace(/^\/|\/$/g, '').toLowerCase() === viewerLogin.toLowerCase()) {
            mention.classList.add('user-mention-viewer');
          }
        } catch (_) { /* 无效链接不标记为当前账号。 */ }
      });
      fragment.content.querySelectorAll('pre').forEach(preservePreNewline);
      window.github.load(id, fragment.innerHTML, suggestions, tasks, -1, 'false');
      installNativeCode(nativeCodeBlocks);
      // 没有保存回调时，服务端提供的控件也不能伪装为可用的保存动作。
      if (!suggestions) {
        document.querySelectorAll('.js-apply-changes').forEach(e => e.replaceChildren());
      }
      document.querySelectorAll('input:not(.task-list-item-checkbox):not(.tlb-checkbox):not(.tlb-issue-reference-number),select,textarea')
        .forEach(e => {e.disabled = true;});
      if (!active) pauseMedia();
      scheduleMeasure();
    },
    codeHeight(id, codeId, height) {
      if (id !== currentId || !Number.isFinite(height) || height <= 0) return;
      const block = nativeCode.find(block => block.id === codeId);
      if (!block || Math.abs(block.element.getBoundingClientRect().height - height) < 0.5) return;
      block.element.style.setProperty('height', height + 'px', 'important');
      scheduleMeasure();
    },
    anchor
  };
  document.addEventListener('click', event => {
    const link = event.target.closest && event.target.closest('a[href]');
    if (!link) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const href = link.getAttribute('href');
    if (href.startsWith('#')) anchor(href);
    else send('open_link', {href});
  }, true);
  document.addEventListener('submit', event => event.preventDefault(), true);
  document.addEventListener('play', event => {
    if (!active && typeof event.target.pause === 'function') event.target.pause();
  }, true);
})();
