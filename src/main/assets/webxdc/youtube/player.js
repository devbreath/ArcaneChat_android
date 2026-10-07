// YouTube Player webxdc: MSE-based progressive playback fed by the webxdc realtime channel.
//
// Roles:
// - initiator: the peer who shared/invited (advertises with role "initiator"); its Android side
//   downloads from googlevideo and serves frames to viewers.
// - viewer: peers who joined later; they request ranges from the initiator.
//
// On the initiator device, Android's InternalJSApi exposes ytFetchUrl()/readVideoChunk()/etc.
// Viewers receive frames via the realtime channel and persist them through writeVideoChunk(); the
// <video> element is fed from the local .part file via interceptRequest URLs (query-params carry
// start/len since WebView's String-based shouldInterceptRequest has no header access).
/* global webxdc, InternalJSApi */

(function () {
  'use strict';

  var FRAME_SIZE = 16384; // must match YoutubeProtocol.FRAME_SIZE
  var CHUNK_SIZE = 524288; // must match YoutubeProtocol.CHUNK_SIZE
  var EOF_WAIT_MS = 30000; // give up waiting for a range after this
  var PREFETCH_SECONDS = 30; // keep at least this much buffered ahead of the playhead

  var video = document.getElementById('video');
  var titleEl = document.getElementById('title');
  var statusEl = document.getElementById('status');
  var inviteEl = document.getElementById('invite');

  var hasJavaBridge = typeof InternalJSApi !== 'undefined';

  var state = {
    videoId: null,
    title: '',
    mime: 'video/mp4; codecs="avc1.64001f,mp4a.40.2"',
    size: 0,
    chunkSize: CHUNK_SIZE,
    frameSize: FRAME_SIZE,
    isInitiator: false, // our device downloads from youtube via the java bridge
    channel: null,
    ms: null, // MediaSource
    sb: null, // SourceBuffer
    pending: {}, // start -> {resolve, reject, timer}
    incoming: {}, // start -> {frames: {seq: bytes}, received: n, eof: bool}
    nextRequestStart: 0,
  };

  function log() {
    var args = Array.prototype.slice.call(arguments);
    console.log.apply(console, ['[yt]'].concat(args));
  }

  function setStatus(text) {
    statusEl.textContent = text;
  }

  // ---- protocol building (kept in sync with YoutubeProtocol.java) ----
  var proto = {
    buildAdv: function (videoId, role) {
      return JSON.stringify({ t: 'adv', videoId: videoId, role: role });
    },
    buildReq: function (videoId, start, len) {
      return JSON.stringify({ t: 'req', videoId: videoId, start: start, len: len });
    },
    buildReqMeta: function (videoId) {
      return JSON.stringify({ t: 'reqmeta', videoId: videoId });
    },
  };

  // ---- realtime channel ----
  function joinChannel() {
    if (state.channel) {
      return;
    }
    state.channel = webxdc.joinRealtimeChannel();
    state.channel.setListener(function (data) {
      try {
        var msg = JSON.parse(new TextDecoder().decode(data));
        handleRealtime(msg);
      } catch (e) {
        log('bad realtime message', e);
      }
    });
    sendRealtime(
      proto.buildAdv(state.videoId || '', state.isInitiator ? 'initiator' : 'viewer')
    );
  }

  function sendRealtime(json) {
    if (!state.channel) {
      return;
    }
    try {
      state.channel.send(new TextEncoder().encode(json));
    } catch (e) {
      log('send failed', e);
    }
  }

  // ---- incoming realtime handling ----
  function handleRealtime(msg) {
    if (!msg || !msg.t) {
      return;
    }
    switch (msg.t) {
      case 'adv':
        onPeerAdv(msg);
        break;
      case 'meta':
        onMeta(msg);
        break;
      case 'cf':
        onChunkFrame(msg);
        break;
      case 'eof':
        onEof(msg);
        break;
      case 'err':
        onErr(msg);
        break;
      case 'req':
      case 'reqmeta':
        // Initiator-side work happens in Java (WebxdcActivity dispatches realtime events
        // directly to YoutubeManager); the JS bridge is not involved in serving requests.
        break;
      default:
        break;
    }
  }

  function onPeerAdv(msg) {
    // Presence announcements need no action: the Java side already sees every realtime message
    // through DC_EVENT_WEBXDC_REALTIME_DATA, and viewers wait for meta before requesting.
  }

  function onMeta(msg) {
    state.size = msg.size;
    state.mime = msg.mime || state.mime;
    if (msg.title) {
      state.title = msg.title;
      titleEl.textContent = msg.title;
    }
    if (state.size > 0 && !state.sb) {
      setupMse();
    }
  }

  function onChunkFrame(msg) {
    var entry = state.incoming[msg.start];
    if (!entry) {
      return;
    }
    var bytes = base64ToBytes(msg.b64);
    var offsetBefore = entry.received;
    entry.frames[msg.seq] = bytes;
    entry.received += bytes.length;
    // Persist frames into the java-side .part file so playback can read the range back through
    // the interceptRequest URL once the chunk is complete.
    if (hasJavaBridge) {
      InternalJSApi.writeVideoChunk(state.videoId, msg.start + offsetBefore, msg.b64);
    }
  }

  function onEof(msg) {
    var req = state.pending[msg.start];
    if (state.incoming[msg.start]) {
      state.incoming[msg.start].eof = true;
    }
    if (req) {
      clearTimeout(req.timer);
      delete state.pending[msg.start];
      req.resolve();
    }
  }

  function onErr(msg) {
    var req = state.pending[msg.start];
    if (req) {
      delete state.pending[msg.start];
      req.reject(new Error('chunk error at ' + msg.start));
    }
  }

  function base64ToBytes(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) {
      bytes[i] = bin.charCodeAt(i);
    }
    return bytes;
  }

  // ---- viewer: range requests over the realtime channel ----
  function requestRange(start, len) {
    return new Promise(function (resolve, reject) {
      if (state.pending[start]) {
        reject(new Error('range already in flight'));
        return;
      }
      var timer = setTimeout(function () {
        delete state.pending[start];
        reject(new Error('range timeout'));
      }, EOF_WAIT_MS);
      state.pending[start] = { resolve: resolve, reject: reject, timer: timer };
      state.incoming[start] = { frames: {}, received: 0, eof: false, len: len };
      sendRealtime(proto.buildReq(state.videoId, start, len));
    });
  }

  // ---- MSE feeding ----
  function setupMse() {
    if (!window.MediaSource) {
      showInvite('MediaSource not supported in this WebView.');
      return;
    }
    var ms = new MediaSource();
    state.ms = ms;
    video.src = URL.createObjectURL(ms);
    ms.addEventListener('sourceopen', function () {
      var sb;
      try {
        sb = ms.addSourceBuffer(state.mime);
      } catch (e) {
        log('sourceBuffer create failed', e);
        setStatus('codec error');
        return;
      }
      state.sb = sb;
      sb.mode = 'segments';
      sb.addEventListener('updateend', pumpLoop);
      pumpLoop();
    });
  }

  function pumpLoop() {
    if (!state.sb || state.sb.updating) {
      return;
    }
    // Free space behind the playhead (rolling window) so long videos do not exhaust the quota.
    if (video.buffered.length > 0 && video.currentTime > 30) {
      var removeEnd = video.currentTime - 30;
      if (state.sb.buffered.length > 0 && state.sb.buffered.start(0) < removeEnd) {
        try {
          state.sb.remove(state.sb.buffered.start(0), removeEnd);
        } catch (e) {
          log('remove failed', e);
        }
        // remove() started, pump continues on updateend
        return;
      }
    }
    var buffered = video.buffered;
    var have = buffered.length > 0 ? buffered.end(buffered.length - 1) : 0;
    if (have < video.currentTime + PREFETCH_SECONDS) {
      fetchNextChunk();
    }
  }

  var inflightFetch = false;
  function fetchNextChunk() {
    if (inflightFetch || state.size === 0) {
      return;
    }
    inflightFetch = true;
    var approxBitrate = (2.5 * 1024 * 1024) / 8; // ~2.5 Mbit/s fallback before size is known
    var chunkIdx = Math.floor((video.currentTime * approxBitrate) / state.chunkSize);
    var start = chunkIdx * state.chunkSize;
    if (start >= state.size) {
      inflightFetch = false;
      return;
    }
    getRange(start, state.chunkSize)
      .then(function (bytes) {
        inflightFetch = false;
        appendBuffer(start, bytes);
      })
      .catch(function (e) {
        inflightFetch = false;
        log('chunk fetch failed', start, e);
      });
  }

  function appendBuffer(start, bytes) {
    if (!state.sb || state.sb.updating) {
      // wait and retry; MSE cannot append while updating
      setTimeout(function () {
        appendBuffer(start, bytes);
      }, 200);
      return;
    }
    try {
      state.sb.appendBuffer(bytes);
    } catch (e) {
      log('appendBuffer failed', e);
    }
  }

  // ---- data plumbing ----
  // initiator: read from the java-managed .part file
  // viewer: request over realtime; frames are written to the .part file by java,
  //         then read back through the interceptRequest URL.
  function getRange(start, len) {
    if (state.isInitiator) {
      return fetchLocalRange(start, len);
    }
    return requestOverRealtime(start, len);
  }

  function fetchLocalRange(start, len) {
    // Ask java to fetch (if missing) and persist the range, then read it back.
    return fetch('/yt/' + state.videoId + '.mp4?start=' + start + '&len=' + len, {
      method: 'GET',
    })
      .then(function (resp) {
        return resp.arrayBuffer();
      })
      .then(function (buf) {
        var u8 = new Uint8Array(buf);
        if (u8Empty(u8len(buf))) {
          throw new Error('empty range response');
        }
        return buf;
      });
  }

  function u8len(b) {
    return b.byteLength;
  }
  function u8Empty(n) {
    return n === 0;
  }

  function requestRangePromise(start, len) {
    return requestRange(start, len).then(function () {
      // after eof, the range is in the .part file; read it via interceptRequest
      return fetch('/yt/' + state.videoId + '.mp4?start=' + start + '&len=' + len)
        .then(function (r) {
          return r.arrayBuffer();
        });
    });
  }
  var requestOverRealtime = requestRangePromise;

  // ---- init flow ----
  function start(videoId, title, isInitiator) {
    state.videoId = videoId;
    state.title = title || videoId;
    state.isInitiator = isInitiator;
    titleEl.textContent = state.title;
    joinChannel();
    if (isInitiator) {
      initAsInitiator();
    } else {
      setupMse();
      sendRealtime(proto.buildReqMeta(videoId));
    }
  }

  function initAsInitiator() {
    fetch('/yt/' + state.videoId + '.mp4?meta=1')
      .then(function (r) {
        return r.json();
      })
      .then(function (meta) {
        state.size = meta.size;
        state.mime = meta.mime || state.mime;
        state.title = meta.title || state.title;
        titleEl.textContent = state.title;
        setupMse();
      })
      .catch(function (e) {
        log('meta fetch failed', e);
        setStatus('meta error');
      });
  }

  function showInvite(videoId, title) {
    inviteEl.style.display = 'block';
    inviteEl.innerHTML = '';
    var text = document.createElement('div');
    text.textContent = 'Invitation from the initiator: watch ' + (title || videoId);
    var play = document.createElement('button');
    text.appendChild(document.createElement('br'));
    text.appendChild(play);
    play.textContent = 'Play';
    play.onclick = function () {
      inviteEl.style.display = 'none';
      start(videoId, title, state.isInitiator);
    };
    inviteEl.appendChild(text);
    inviteEl.style.display = 'block';
  }

  // ---- status updates (invitations) ----
  webxdc.setUpdateListener(function (update) {
    var payload = update.payload;
    if (!payload || payload.kind !== 'yt') {
      return;
    }
    if (payload.videoId && !/^[A-Za-z0-9_-]{11}$/.test(payload.videoId)) {
      log('ignoring invite with invalid videoId', payload.videoId);
      return;
    }
    // The initiator is the peer that sent the invite payload; everyone else (including
    // ourselves receiving our own status update) watches via the realtime channel.
    var isSelf = payload.from && payload.from === webxdc.selfAddr;
    if (!state.videoId) {
      state.videoId = payload.videoId;
      state.title = payload.title || '';
      state.ownInvite = isSelf;
      titleEl.textContent = payload.title || payload.videoId;
      joinChannel();
      if (isSelf) {
        // our own invite: start playing right away, we are the P2P server
        start(payload.videoId, payload.title, true);
      } else {
        showInvite(payload.videoId, payload.title);
      }
    }
  });

  // If no invitation arrived (player opened directly), show a small url prompt.
  if (!state.videoId) {
    setTimeout(function () {
      if (!state.videoId) {
        var input = document.createElement('input');
        var btn = document.createElement('button');
        btn.textContent = 'Watch';
        btn.onclick = function () {
          var m = (input.value || '').match(/(?:v=|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})/);
          if (m) {
            start(m[1], '', true);
          }
        };
        inviteEl.appendChild(input);
        inviteEl.appendChild(btn);
        inviteEl.style.display = 'block';
      }
    }, 800);
  }
})();
