import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { AnimatePresence, motion } from 'framer-motion';
import { Wifi, Smartphone, Monitor } from 'lucide-react';

type ToastState = 'waiting' | 'pending' | 'picking' | 'sending' | 'sent' | 'error';

interface Device {
  id: string;
  label: string;
  platform: string;
}

const DISMISS_DELAY_MS = 8000;

export default function ScreenshotToastApp() {
  const [toastState, setToastState]   = useState<ToastState>('waiting');
  const [pendingPath, setPendingPath] = useState<string | null>(null);
  const [errorMsg, setErrorMsg]       = useState<string | null>(null);
  const [devices, setDevices]         = useState<Device[]>([]);
  const [sentTarget, setSentTarget]   = useState<string>('room');

  const dismissTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const toastStateRef   = useRef<ToastState>('waiting');
  const devicesRef      = useRef<Device[]>([]);

  useEffect(() => { toastStateRef.current = toastState; }, [toastState]);
  useEffect(() => { devicesRef.current    = devices; },    [devices]);

  useLayoutEffect(() => {
    document.documentElement.classList.add('screenshot-toast-window');
    document.body.classList.add('screenshot-toast-window');
    return () => {
      document.documentElement.classList.remove('screenshot-toast-window');
      document.body.classList.remove('screenshot-toast-window');
    };
  }, []);

  // ── timers ──────────────────────────────────────────────────────────────────
  const clearDismissTimer = () => {
    if (dismissTimerRef.current) { clearTimeout(dismissTimerRef.current); dismissTimerRef.current = null; }
  };

  const scheduleDismiss = (delayMs: number) => {
    clearDismissTimer();
    dismissTimerRef.current = setTimeout(() => {
      setToastState('waiting');
      setPendingPath(null);
      void invoke('screenshot_toast_dismiss').catch(() => {});
    }, delayMs);
  };

  // ── poll for devices + pending screenshot ───────────────────────────────────
  const checkPending = () => {
    // Always refresh device list from Rust registry
    invoke<Device[]>('get_room_devices')
      .then((devs) => setDevices(devs))
      .catch(() => {});

    if (toastStateRef.current !== 'waiting') return;
    invoke<string | null>('get_pending_screenshot')
      .then((path) => {
        if (path) {
          setPendingPath(path);
          setToastState('picking');
          scheduleDismiss(DISMISS_DELAY_MS);
        }
      })
      .catch(() => {});
  };

  useEffect(() => {
    checkPending();
    const onFocus = () => checkPending();
    const onVis   = () => { if (document.visibilityState === 'visible') checkPending(); };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVis);
    const pollId = setInterval(checkPending, 300);
    return () => {
      clearDismissTimer();
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVis);
      clearInterval(pollId);
    };
  }, []);

  // ── actions ─────────────────────────────────────────────────────────────────
  const sendTo = async (targetId: string | null) => {
    const path = pendingPath;
    if (!path) return;
    setToastState('sending');
    const label = targetId
      ? (devicesRef.current.find((d) => d.id === targetId)?.label ?? targetId)
      : 'room';
    setSentTarget(label);
    try {
      await invoke('upload_screenshot', { path, target: targetId });
      setToastState('sent');
      scheduleDismiss(1500);
    } catch (err) {
      const msg = typeof err === 'string' ? err : (err as any)?.message ?? 'Unknown error';
      setErrorMsg(msg);
      setToastState('error');
      scheduleDismiss(4000);
    }
  };

  const handleDismiss = () => {
    clearDismissTimer();
    setToastState('waiting');
    setPendingPath(null);
    invoke('screenshot_toast_dismiss').catch(() => {});
  };

  if (toastState === 'waiting') return null;

  const filename = pendingPath?.split(/[\\/]/).pop() ?? 'screenshot.png';

  return (
    <div
      style={{
        position: 'fixed',
        top: 12, left: 12, right: 12,
        display: 'flex',
        flexDirection: 'column',
        pointerEvents: 'none',
      }}
    >
      <motion.div
        initial={{ opacity: 0, y: -8, scale: 0.97 }}
        animate={{ opacity: 1, y: 0,  scale: 1 }}
        style={{
          pointerEvents: 'auto',
          background: 'linear-gradient(160deg, rgba(28,28,34,0.99), rgba(20,20,26,0.97))',
          backdropFilter: 'blur(32px) saturate(1.4)',
          border: '1px solid rgba(255,255,255,0.1)',
          borderRadius: 14,
          overflow: 'hidden',
          boxShadow: '0 20px 48px rgba(0,0,0,0.65), inset 0 1px 0 rgba(255,255,255,0.06)',
          fontFamily: 'system-ui, -apple-system, sans-serif',
        }}
        transition={{ type: 'spring', stiffness: 320, damping: 26 }}
      >
        {/* Preview row */}
        <div style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '12px 13px',
          borderBottom: '1px solid rgba(255,255,255,0.06)',
          background: 'rgba(255,255,255,0.025)',
        }}>
          {/* Thumb */}
          <div style={{
            width: 40, height: 40, borderRadius: 8, flexShrink: 0,
            background: toastState === 'sent'
              ? 'rgba(52,211,153,0.12)'
              : 'linear-gradient(135deg, #1a1a2e, #0f0f1a)',
            border: toastState === 'sent'
              ? '1px solid rgba(52,211,153,0.3)'
              : '1px solid rgba(255,255,255,0.1)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            transition: 'all 0.3s',
          }}>
            {toastState === 'sent' ? (
              <svg width="18" height="18" viewBox="0 0 16 16" fill="none">
                <path d="M3 8l3.5 3.5L13 4.5" stroke="#34d399" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            ) : toastState === 'error' ? (
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path d="M8 4v4M8 11.5v.5" stroke="#f87171" strokeWidth="2" strokeLinecap="round"/>
              </svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="rgba(251,191,36,0.5)" strokeWidth="1.5">
                <rect x="3" y="3" width="18" height="18" rx="2"/>
                <circle cx="8.5" cy="8.5" r="1.5"/>
                <polyline points="21 15 16 10 5 21"/>
              </svg>
            )}
          </div>

          {/* Meta */}
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'rgba(244,244,245,0.9)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {filename}
            </div>
            <div style={{ fontSize: 10, color: toastState === 'sent' ? '#34d399' : toastState === 'error' ? '#f87171' : 'rgba(244,244,245,0.38)', marginTop: 2 }}>
              {toastState === 'sending' ? 'Uploading…'
                : toastState === 'sent'    ? `Sent to ${sentTarget}`
                : toastState === 'error'   ? (errorMsg ?? 'Upload failed')
                : 'Choose a device'}
            </div>
          </div>

          {/* Close */}
          <button
            onClick={handleDismiss}
            style={{
              width: 22, height: 22, borderRadius: 6, flexShrink: 0,
              background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.07)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer', color: 'rgba(244,244,245,0.35)',
            }}
          >
            <svg width="9" height="9" viewBox="0 0 10 10" fill="none">
              <path d="M1 1l8 8M9 1L1 9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"/>
            </svg>
          </button>
        </div>

        {/* Sending spinner */}
        <AnimatePresence>
          {toastState === 'sending' && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.15 }}
              style={{ overflow: 'hidden' }}
            >
              <div style={{ padding: '8px 12px 12px', display: 'flex', justifyContent: 'center' }}>
                <div style={{ fontSize: 11, color: 'rgba(244,244,245,0.35)', fontStyle: 'italic' }}>Uploading…</div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Device picker — slides open */}
        <AnimatePresence>
          {toastState === 'picking' && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ type: 'spring', stiffness: 300, damping: 28 }}
              style={{ overflow: 'hidden' }}
            >
              <div style={{ padding: '8px 8px 10px' }}>
                <div style={{
                  fontSize: 10, fontWeight: 700, letterSpacing: '0.1em',
                  textTransform: 'uppercase', color: 'rgba(244,244,245,0.28)',
                  padding: '0 4px 6px',
                }}>
                  Send to
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  {/* Broadcast */}
                  <motion.button
                    initial={{ opacity: 0, x: -8 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: 0.05 }}
                    onClick={() => sendTo(null)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 10,
                      padding: '9px 10px', borderRadius: 8, cursor: 'pointer',
                      background: 'rgba(251,191,36,0.08)', border: '1px solid rgba(251,191,36,0.15)',
                      fontFamily: 'inherit',
                    }}
                  >
                    <div style={{
                      width: 26, height: 26, borderRadius: 7, flexShrink: 0,
                      background: 'rgba(251,191,36,0.14)', border: '1px solid rgba(251,191,36,0.2)',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                    }}>
                      <Wifi size={12} color="#fbbf24" />
                    </div>
                    <span style={{ fontSize: 12, fontWeight: 500, color: '#fbbf24' }}>All devices</span>
                  </motion.button>

                  {/* Individual devices */}
                  {devices.map((device, i) => {
                    const Icon = device.platform === 'android' ? Smartphone : Monitor;
                    const iconColor = device.platform === 'android' ? '#34d399' : '#60a5fa';
                    const bgColor   = device.platform === 'android'
                      ? 'rgba(52,211,153,0.12)'  : 'rgba(96,165,250,0.12)';
                    const bdColor   = device.platform === 'android'
                      ? 'rgba(52,211,153,0.2)'   : 'rgba(96,165,250,0.2)';

                    return (
                      <motion.button
                        key={device.id}
                        initial={{ opacity: 0, x: -8 }}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ delay: (i + 1) * 0.06 + 0.05 }}
                        onClick={() => sendTo(device.id)}
                        style={{
                          display: 'flex', alignItems: 'center', gap: 10,
                          padding: '9px 10px', borderRadius: 8, cursor: 'pointer',
                          background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.07)',
                          fontFamily: 'inherit',
                        }}
                      >
                        <div style={{
                          width: 26, height: 26, borderRadius: 7, flexShrink: 0,
                          background: bgColor, border: `1px solid ${bdColor}`,
                          display: 'flex', alignItems: 'center', justifyContent: 'center',
                        }}>
                          <Icon size={12} color={iconColor} />
                        </div>
                        <div style={{ flex: 1, textAlign: 'left' }}>
                          <div style={{ fontSize: 12, fontWeight: 500, color: 'rgba(244,244,245,0.85)' }}>{device.label}</div>
                          <div style={{ fontSize: 10, color: 'rgba(244,244,245,0.35)', textTransform: 'capitalize', marginTop: 1 }}>{device.platform}</div>
                        </div>
                      </motion.button>
                    );
                  })}
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </div>
  );
}
