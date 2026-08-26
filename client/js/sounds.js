// Web Audio API Synthesizer for Call Sounds (Ringtone, Dialing, Connected, Ended, Busy)

class CallSoundEffects {
  constructor() {
    this.ctx = null;
    this._currentLoop = null;
    this._loopTimeout = null;
  }

  _getAudioContext() {
    if (!this.ctx || this.ctx.state === 'closed') {
      const AudioCtx = window.AudioContext || /** @type {any} */ (window).webkitAudioContext;
      if (AudioCtx) {
        this.ctx = new AudioCtx();
      }
    }
    if (this.ctx && this.ctx.state === 'suspended') {
      this.ctx.resume().catch(() => {});
    }
    return this.ctx;
  }

  stopAll() {
    if (this._loopTimeout) {
      clearTimeout(this._loopTimeout);
      this._loopTimeout = null;
    }
    if (this._currentLoop && typeof this._currentLoop.stop === 'function') {
      try {
        this._currentLoop.stop();
      } catch (_) {}
      this._currentLoop = null;
    }
  }

  /**
   * Play a melodious repeating ringtone for incoming calls.
   */
  playRingtone() {
    this.stopAll();
    const ctx = this._getAudioContext();
    if (!ctx) return;

    let isStopped = false;

    const playMelodyPhrase = () => {
      if (isStopped) return;
      const now = ctx.currentTime;
      
      // Melodic notes sequence [freq, startOffset, duration]
      const notes = [
        [659.25, 0.00, 0.22], // E5
        [830.61, 0.18, 0.22], // G#5
        [987.77, 0.36, 0.22], // B5
        [1318.51, 0.54, 0.35], // E6
        [1174.66, 0.85, 0.22], // D6
        [987.77, 1.05, 0.22],  // B5
        [830.61, 1.25, 0.40],  // G#5
      ];

      notes.forEach(([freq, offset, dur]) => {
        const osc = ctx.createOscillator();
        const osc2 = ctx.createOscillator();
        const gain = ctx.createGain();

        // Primary tone: warm sine
        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, now + offset);

        // Secondary subtle harmonic: triangle for body
        osc2.type = 'triangle';
        osc2.frequency.setValueAtTime(freq, now + offset);

        gain.gain.setValueAtTime(0.0001, now + offset);
        gain.gain.exponentialRampToValueAtTime(0.18, now + offset + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + dur);

        osc.connect(gain);
        osc2.connect(gain);
        gain.connect(ctx.destination);

        osc.start(now + offset);
        osc2.start(now + offset);
        osc.stop(now + offset + dur);
        osc2.stop(now + offset + dur);
      });

      this._loopTimeout = setTimeout(() => {
        if (!isStopped) {
          playMelodyPhrase();
        }
      }, 2600);
    };

    this._currentLoop = {
      stop: () => {
        isStopped = true;
      }
    };

    playMelodyPhrase();
  }

  /**
   * Play outgoing call dialing tone (гудки: 425Hz, 1.0s on, 2.0s off).
   */
  playDialing() {
    this.stopAll();
    const ctx = this._getAudioContext();
    if (!ctx) return;

    let isStopped = false;

    const playBeep = () => {
      if (isStopped) return;
      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      const osc2 = ctx.createOscillator();
      const gain = ctx.createGain();

      // Dual tone: 425Hz + subtle 450Hz for depth
      osc.type = 'sine';
      osc.frequency.setValueAtTime(425, now);
      osc2.type = 'sine';
      osc2.frequency.setValueAtTime(450, now);

      gain.gain.setValueAtTime(0.0001, now);
      gain.gain.linearRampToValueAtTime(0.12, now + 0.05);
      gain.gain.setValueAtTime(0.12, now + 0.95);
      gain.gain.linearRampToValueAtTime(0.0001, now + 1.00);

      osc.connect(gain);
      osc2.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now);
      osc2.start(now);
      osc.stop(now + 1.02);
      osc2.stop(now + 1.02);

      this._loopTimeout = setTimeout(() => {
        if (!isStopped) {
          playBeep();
        }
      }, 3000);
    };

    this._currentLoop = {
      stop: () => {
        isStopped = true;
      }
    };

    playBeep();
  }

  /**
   * Play short connected chime when call begins.
   */
  playConnected() {
    this.stopAll();
    const ctx = this._getAudioContext();
    if (!ctx) return;

    const now = ctx.currentTime;
    const notes = [
      [523.25, 0.00, 0.12], // C5
      [659.25, 0.09, 0.12], // E5
      [783.99, 0.18, 0.30], // G5
    ];

    notes.forEach(([freq, offset, dur]) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now + offset);

      gain.gain.setValueAtTime(0.0001, now + offset);
      gain.gain.exponentialRampToValueAtTime(0.15, now + offset + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + dur);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now + offset);
      osc.stop(now + offset + dur);
    });
  }

  /**
   * Play short call ended sound.
   */
  playEnded() {
    this.stopAll();
    const ctx = this._getAudioContext();
    if (!ctx) return;

    const now = ctx.currentTime;
    const notes = [
      [783.99, 0.00, 0.14], // G5
      [523.25, 0.10, 0.30], // C5
    ];

    notes.forEach(([freq, offset, dur]) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now + offset);

      gain.gain.setValueAtTime(0.0001, now + offset);
      gain.gain.exponentialRampToValueAtTime(0.14, now + offset + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + dur);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now + offset);
      osc.stop(now + offset + dur);
    });
  }

  /**
   * Play busy cadence (3 short beeps: 425Hz, 0.25s on, 0.25s off).
   */
  playBusy() {
    this.stopAll();
    const ctx = this._getAudioContext();
    if (!ctx) return;

    const now = ctx.currentTime;
    for (let i = 0; i < 3; i++) {
      const offset = i * 0.40;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(425, now + offset);

      gain.gain.setValueAtTime(0.0001, now + offset);
      gain.gain.linearRampToValueAtTime(0.12, now + offset + 0.02);
      gain.gain.setValueAtTime(0.12, now + offset + 0.23);
      gain.gain.linearRampToValueAtTime(0.0001, now + offset + 0.25);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now + offset);
      osc.stop(now + offset + 0.26);
    }
  }
}

export const callSounds = new CallSoundEffects();
