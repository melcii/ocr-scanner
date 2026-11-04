(() => {
  const $ = (id) => document.getElementById(id);
  const MAX_SIDE = 2200;               // downscale huge photos: faster, same accuracy
  const bridge = window.AndroidBridge; // provided by the Android shell (absent in a desktop browser)

  // Pick the right engine build: SIMD where the WebView supports it, plain otherwise.
  const simd = WebAssembly.validate(new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,96,0,1,123,3,2,1,0,10,10,1,8,0,65,0,253,15,253,98,11]));
  const corePath = new URL('core/' + (simd ? 'tesseract-core-simd-lstm.wasm.js' : 'tesseract-core-lstm.wasm.js'), location.href).href;
  const base = new URL('.', location.href).href;

  let lang = 'eng', worker = null, workerLang = null, busy = false, current = null, rotation = 0, stream = null;

  function toast(msg) {
    const t = $('toast'); t.textContent = msg; t.classList.add('show');
    clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 1800);
  }
  function setProgress(label, p) {
    $('progress').hidden = false; $('pstatus').textContent = label;
    $('pbar').style.width = Math.round((p || 0) * 100) + '%';
  }

  async function getWorker() {
    if (worker && workerLang === lang) return worker;
    if (worker) { await worker.terminate(); worker = null; }
    setProgress('Loading language…', 0);
    worker = await Tesseract.createWorker(lang.split('+'), 1, {
      workerPath: base + 'worker.min.js',
      corePath,
      langPath: base + 'lang',
      gzip: true,
      cacheMethod: 'none',
      logger: (m) => {
        if (m.status === 'recognizing text') setProgress('Reading text…', m.progress);
        else if (m.status) setProgress(m.status.charAt(0).toUpperCase() + m.status.slice(1) + '…', m.progress);
      }
    });
    workerLang = lang;
    return worker;
  }

  // Draw the source (image/video) to a canvas, scaled down and rotated.
  function toCanvas(src, w, h, rot) {
    const s = Math.min(1, MAX_SIDE / Math.max(w, h));
    const sw = Math.round(w * s), sh = Math.round(h * s);
    const c = document.createElement('canvas');
    const swap = rot % 180 !== 0;
    c.width = swap ? sh : sw; c.height = swap ? sw : sh;
    const g = c.getContext('2d');
    g.translate(c.width / 2, c.height / 2); g.rotate(rot * Math.PI / 180);
    g.drawImage(src, -sw / 2, -sh / 2, sw, sh);
    return c;
  }

  async function run() {
    if (!current || busy) return;
    busy = true;
    const canvas = toCanvas(current, current.naturalWidth || current.width, current.naturalHeight || current.height, rotation);
    $('preview').src = canvas.toDataURL('image/jpeg', 0.9);
    $('preview').hidden = false; $('empty').hidden = true; $('tools').hidden = false;
    $('out').value = ''; $('meta').textContent = 'Working…';
    const t0 = performance.now();
    try {
      const w = await getWorker();
      const { data } = await w.recognize(canvas);
      const text = (data.text || '').replace(/\n{3,}/g, '\n\n').trim();
      $('out').value = text;
      const words = text ? text.split(/\s+/).length : 0;
      const secs = ((performance.now() - t0) / 1000).toFixed(1);
      $('meta').textContent = words ? `${words} words · ${Math.round(data.confidence)}% · ${secs}s` : 'No text found — try a sharper, closer shot';
    } catch (e) {
      console.error(e);
      $('meta').textContent = 'Something went wrong: ' + (e && e.message || e);
    } finally {
      $('progress').hidden = true; busy = false;
    }
  }

  function loadImage(url) {
    const img = new Image();
    img.onload = () => { current = img; rotation = 0; run(); };
    img.onerror = () => toast('Could not open that image');
    img.src = url;
  }

  // Language chips
  document.querySelectorAll('.chip').forEach((b) => b.addEventListener('click', () => {
    if (busy) return;
    document.querySelectorAll('.chip').forEach((c) => c.setAttribute('aria-pressed', String(c === b)));
    lang = b.dataset.lang;
    if (current) run();
  }));

  // Gallery
  $('galBtn').onclick = () => $('file').click();
  $('file').onchange = (e) => {
    const f = e.target.files && e.target.files[0];
    if (f) loadImage(URL.createObjectURL(f));
    e.target.value = '';
  };

  $('rotate').onclick = () => { if (!busy) { rotation = (rotation + 90) % 360; run(); } };

  // Camera
  async function openCamera() {
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 3840 }, height: { ideal: 2160 } }
      });
      $('video').srcObject = stream;
      $('camera').classList.add('open');
      if (bridge && bridge.setCameraOpen) bridge.setCameraOpen(true);
      const track = stream.getVideoTracks()[0];
      const caps = track.getCapabilities ? track.getCapabilities() : {};
      $('camTorch').hidden = !caps.torch;
      if (caps.focusMode && caps.focusMode.includes('continuous')) track.applyConstraints({ advanced: [{ focusMode: 'continuous' }] }).catch(() => {});
    } catch (e) {
      console.error(e);
      toast('Camera unavailable — use Gallery instead');
    }
  }
  function closeCamera() {
    if (stream) stream.getTracks().forEach((t) => t.stop());
    stream = null; torchOn = false;
    $('camera').classList.remove('open');
    if (bridge && bridge.setCameraOpen) bridge.setCameraOpen(false);
  }
  let torchOn = false;
  $('camBtn').onclick = openCamera;
  $('camClose').onclick = closeCamera;
  $('camTorch').onclick = () => {
    const track = stream && stream.getVideoTracks()[0];
    if (!track) return;
    torchOn = !torchOn;
    track.applyConstraints({ advanced: [{ torch: torchOn }] }).catch(() => {});
  };
  $('shutter').onclick = () => {
    const v = $('video');
    if (!v.videoWidth) return;
    const c = document.createElement('canvas');
    c.width = v.videoWidth; c.height = v.videoHeight;
    c.getContext('2d').drawImage(v, 0, 0);
    closeCamera();
    current = c; rotation = 0; run();
  };
  window.onBackPressed = () => {         // called by the Android shell's back handler
    if ($('camera').classList.contains('open')) { closeCamera(); return true; }
    return false;
  };


  // Images handed over by Android (Share menu, screenshot notification)
  window.openSharedImage = (url) => {
    if ($('camera').classList.contains('open')) closeCamera();
    loadImage(url);
  };

  // Screenshot alerts setting (only inside the Android app)
  const DEFAULT_ALERT_MSG = 'Offer to scan right after you take a screenshot';
  if (bridge && bridge.getScreenshotAlerts) {
    $('alertRow').hidden = false;
    $('alertToggle').checked = !!bridge.getScreenshotAlerts();
    $('alertToggle').onchange = (e) => {
      $('alertMsg').textContent = e.target.checked ? 'Asking for permission…' : DEFAULT_ALERT_MSG;
      $('alertMsg').classList.remove('warn');
      bridge.setScreenshotAlerts(e.target.checked);
    };
  }
  window.onScreenshotAlerts = (on, msg) => {
    $('alertToggle').checked = on;
    $('alertMsg').textContent = msg || (on ? 'On — you\'ll get a notification after each screenshot' : DEFAULT_ALERT_MSG);
    $('alertMsg').classList.toggle('warn', !!msg);
    if (on) toast('Screenshot alerts on');
  };

  // Copy / share
  $('copyBtn').onclick = async () => {
    const text = $('out').value;
    if (!text) return toast('Nothing to copy yet');
    if (bridge) { bridge.copy(text); return toast('Copied'); }
    try { await navigator.clipboard.writeText(text); toast('Copied'); } catch { $('out').select(); document.execCommand('copy'); toast('Copied'); }
  };
  $('shareBtn').onclick = () => {
    const text = $('out').value;
    if (!text) return toast('Nothing to share yet');
    if (bridge) bridge.share(text);
    else if (navigator.share) navigator.share({ text }).catch(() => {});
    else toast('Sharing not supported here');
  };
})();
