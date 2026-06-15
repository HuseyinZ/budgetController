/* ====================================================================
   Sound — PWA için sesli bildirim (Web Audio API)
   ====================================================================
   Bağlı hoparlöre / kulaklığa / telefon iç hoparlörüne ses çalar.
   WAV dosya yok — programatik sinüs tonu üretir, herhangi bir kütüphane
   gerektirmez. iOS Safari'de ilk dokunma gerekir (autoplay policy).
   ==================================================================== */

const Sound = (() => {
  let ctx = null;
  let unlocked = false;
  let enabled = (localStorage.getItem('sound') !== 'off');

  /** AudioContext oluştur (lazy — ilk ses çağrılınca). */
  function getCtx() {
    if (!ctx) {
      try {
        ctx = new (window.AudioContext || window.webkitAudioContext)();
      } catch (e) {
        return null;
      }
    }
    return ctx;
  }

  /**
   * iOS Safari + diğer mobil tarayıcılar autoplay'i ilk kullanıcı
   * etkileşimine kadar bloklar. İlk dokun → AudioContext'i resume et.
   */
  function unlock() {
    if (unlocked) return;
    const c = getCtx();
    if (!c) return;
    if (c.state === 'suspended') {
      c.resume().catch(() => {});
    }
    // Sessiz bir bip çal — context'i resmen "etkin" yapar
    const osc = c.createOscillator();
    const gain = c.createGain();
    gain.gain.value = 0;
    osc.connect(gain).connect(c.destination);
    osc.start(0);
    osc.stop(c.currentTime + 0.001);
    unlocked = true;
  }

  /** Belirli frekansta belirli süre çal. */
  function tone(freq, durationMs, volume = 0.4) {
    if (!enabled) return;
    const c = getCtx();
    if (!c) return;
    const t0 = c.currentTime;
    const dur = durationMs / 1000;
    const osc = c.createOscillator();
    const gain = c.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(freq, t0);
    // Fade in/out — click sesi olmasın
    gain.gain.setValueAtTime(0, t0);
    gain.gain.linearRampToValueAtTime(volume, t0 + 0.01);
    gain.gain.linearRampToValueAtTime(volume, t0 + dur - 0.02);
    gain.gain.linearRampToValueAtTime(0, t0 + dur);
    osc.connect(gain).connect(c.destination);
    osc.start(t0);
    osc.stop(t0 + dur);
  }

  /** Bir dizi tonu sırayla çal. */
  function tones(freqs, durationMs) {
    if (!enabled) return;
    const c = getCtx();
    if (!c) return;
    let delay = 0;
    for (const f of freqs) {
      setTimeout(() => tone(f, durationMs), delay);
      delay += durationMs + 20;
    }
  }

  /** Olay türleri — Java SoundService ile aynı. */
  const events = {
    newOrder:     () => tones([880, 1175], 100),
    kitchenSent:  () => tones([660, 880, 1100], 90),
    orderReady:   () => tones([1175, 1175, 1175], 130),
    saleComplete: () => tones([523, 659, 784, 1047], 110),
    error:        () => tones([440, 330], 180),
  };

  function setEnabled(value) {
    enabled = !!value;
    localStorage.setItem('sound', enabled ? 'on' : 'off');
  }

  function isEnabled() { return enabled; }

  return {
    unlock,
    play: (eventName) => {
      const fn = events[eventName];
      if (fn) fn();
    },
    setEnabled,
    isEnabled
  };
})();

// İlk kullanıcı etkileşiminde AudioContext'i unlock et (iOS şart koşar)
['click', 'touchstart', 'keydown'].forEach(evt => {
  document.addEventListener(evt, () => Sound.unlock(), { once: true });
});
