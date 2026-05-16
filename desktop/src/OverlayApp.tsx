import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { listen, emit } from '@tauri-apps/api/event';
import { invoke } from '@tauri-apps/api/core';
import { AnimatePresence, motion } from 'framer-motion';
import { Wifi, Smartphone, Monitor } from 'lucide-react';

type ShelfState = 'hidden' | 'bloomed' | 'ready' | 'accepted' | 'picking';

interface Device {
  id: string;
  label: string;
  platform: string;
}

export default function OverlayApp() {
  const [state, setState] = useState<ShelfState>('hidden');
  const [draggedFileCount, setDraggedFileCount] = useState(0);
  const [devices, setDevices] = useState<Device[]>([]);
  const stateRef = useRef<ShelfState>('hidden');
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const devicesRef = useRef<Device[]>([]);
  const droppedFilesRef = useRef<string[]>([]);
  const dropReceivedRef = useRef(false);
  const hasDraggedFiles = draggedFileCount > 0;

  useLayoutEffect(() => {
    document.documentElement.classList.add('overlay-window');
    document.body.classList.add('overlay-window');
    return () => {
      document.documentElement.classList.remove('overlay-window');
      document.body.classList.remove('overlay-window');
    };
  }, []);

  // Keep devicesRef in sync with devices state
  useEffect(() => {
    devicesRef.current = devices;
  }, [devices]);

  // Seed device list from registry — picks up devices that joined before
  // this window's event listeners were registered (e.g. Android already connected).
  useEffect(() => {
    invoke<{ id: string; label: string; platform: string }[]>('get_room_devices')
      .then((devs) => {
        if (devs.length > 0) setDevices(devs);
      })
      .catch(() => {});
  }, []);

  const setShelfState = (next: ShelfState) => {
    stateRef.current = next;
    setState(next);
  };

  const clearHideTimer = () => {
    if (hideTimerRef.current) {
      clearTimeout(hideTimerRef.current);
      hideTimerRef.current = null;
    }
  };

  const scheduleHide = (delayMs: number) => {
    clearHideTimer();
    hideTimerRef.current = setTimeout(() => {
      setDraggedFileCount(0);
      setShelfState('hidden');
      void invoke('overlay_hide');
    }, delayMs);
  };

  const [uploadError, setUploadError] = useState<string | null>(null);

  const uploadFilesToDevice = async (targetDeviceId: string | null): Promise<boolean> => {
    const files = droppedFilesRef.current;
    if (files.length === 0) return true;

    setUploadError(null);
    const errors: string[] = [];
    for (const filePath of files) {
      const name = filePath.split(/[\\/]/).pop() ?? 'file';
      try {
        const downloadUrl = await invoke<string>('upload_file', {
          path: filePath,
          target: targetDeviceId ?? undefined,
        });
        await emit('overlay:file_uploaded', { name, downloadUrl });
      } catch (err) {
        const msg = typeof err === 'string' ? err : (err as any)?.message ?? 'Upload failed';
        console.error('Upload failed:', filePath, err);
        errors.push(`${name}: ${msg}`);
      }
    }
    if (errors.length > 0) {
      setUploadError(errors.join('\n'));
      return false;
    }
    return true;
  };

  const handleDeviceSelect = async (deviceId: string | null) => {
    const ok = await uploadFilesToDevice(deviceId);
    if (ok) {
      setShelfState('accepted');
      scheduleHide(1200);
    }
    // on error: stay in 'picking' state so user sees the error and can retry
  };

  const handleEscapeKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && stateRef.current === 'picking') {
      droppedFilesRef.current = [];
      setDraggedFileCount(0);
      setShelfState('hidden');
      void invoke('overlay_hide');
    }
  };

  useEffect(() => {
    const cleanups: Array<() => void> = [];

    listen<{ deviceId: string; deviceName: string; platform: string }>('relay:device_join', ({ payload }) => {
      setDevices((prev) => {
        const exists = prev.some((d) => d.id === payload.deviceId);
        return exists
          ? prev
          : [...prev, { id: payload.deviceId, label: payload.deviceName, platform: payload.platform }];
      });
    }).then((u) => cleanups.push(u));

    listen<{ deviceId: string }>('relay:device_leave', ({ payload }) => {
      setDevices((prev) => prev.filter((d) => d.id !== payload.deviceId));
    }).then((u) => cleanups.push(u));

    listen('shelf:drag_start', () => {
      clearHideTimer();
      dropReceivedRef.current = false;
      setDraggedFileCount(0);
      setShelfState('bloomed');
    }).then((u) => cleanups.push(u));

    listen<{ files?: string[] }>('shelf:drag_enter', ({ payload }) => {
      if (stateRef.current !== 'hidden') {
        setDraggedFileCount(Array.isArray(payload?.files) ? payload.files.length : 0);
        setShelfState('ready');
      }
    }).then((u) => cleanups.push(u));

    listen('shelf:drag_leave', () => {
      if (stateRef.current === 'ready') {
        setDraggedFileCount(0);
        setShelfState('bloomed');
      }
    }).then((u) => cleanups.push(u));

    listen<{ files: string[] }>('shelf:drop', ({ payload }) => {
      dropReceivedRef.current = true;
      const files = Array.from(new Set((payload?.files ?? []).filter(Boolean)));
      if (files.length === 0) {
        setDraggedFileCount(0);
        setShelfState('bloomed');
        scheduleHide(240);
        return;
      }

      droppedFilesRef.current = files;
      setDraggedFileCount(files.length);
      // Always show picker — never auto-broadcast without user selection
      setShelfState('picking');
    }).then((u) => cleanups.push(u));

    listen('shelf:drag_end', () => {
      // Wait long enough for shelf:drop to arrive before deciding whether to hide.
      // If a drop was received, the picker is showing — never auto-hide it.
      setTimeout(() => {
        if (dropReceivedRef.current) return;
        if (stateRef.current !== 'accepted' && stateRef.current !== 'picking') {
          clearHideTimer();
          setDraggedFileCount(0);
          setShelfState('hidden');
          void invoke('overlay_hide');
        }
      }, 500);
    }).then((u) => cleanups.push(u));

    window.addEventListener('keydown', handleEscapeKey);

    return () => {
      window.removeEventListener('keydown', handleEscapeKey);
      clearHideTimer();
      cleanups.forEach((fn) => fn());
    };
  }, []);

  const visible = state !== 'hidden';
  const shelfTitle = state === 'accepted'
    ? 'Synced'
    : state === 'ready'
      ? 'Drop to sync'
      : 'Drop files';
  const shelfSubtitle = state === 'accepted'
    ? draggedFileCount > 1
      ? `${draggedFileCount} files moved to LiveC`
      : 'File moved to LiveC'
    : state === 'ready'
      ? hasDraggedFiles && draggedFileCount > 1
        ? `${draggedFileCount} files detected`
        : 'Release to upload'
      : 'Drag from Explorer';

  const coreBackground =
    state === 'accepted'
      ? 'linear-gradient(135deg, rgba(52,211,153,0.46), rgba(52,211,153,0.24))'
      : state === 'ready'
        ? 'linear-gradient(135deg, rgba(251,191,36,0.38), rgba(251,191,36,0.18))'
        : 'linear-gradient(135deg, rgba(39,39,42,0.85), rgba(39,39,42,0.45))';

  const coreBorder =
    state === 'ready'
      ? '1px solid rgba(251,191,36,0.4)'
      : state === 'accepted'
        ? '1px solid rgba(52,211,153,0.4)'
        : '1px solid rgba(255,255,255,0.08)';

  const coreShadow =
    state === 'ready' || state === 'accepted'
      ? '0 20px 48px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.15)'
      : '0 20px 48px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.08)';

  return (
    <div className="w-full h-full flex items-center justify-center pointer-events-none select-none relative">
      {/* Device Picker */}
      <AnimatePresence>
        {state === 'picking' && (
          <motion.div
            initial={{ opacity: 0, scale: 0.9, y: 10 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.9, y: 10 }}
            transition={{ type: 'spring', stiffness: 300, damping: 25 }}
            className="absolute pointer-events-auto"
            style={{
              width: 300,
              bottom: 0,
              right: 0,
              background: 'linear-gradient(135deg, rgba(39,39,42,0.96), rgba(39,39,42,0.88))',
              backdropFilter: 'blur(32px) saturate(1.2)',
              border: '1px solid rgba(255,255,255,0.12)',
              borderRadius: 16,
              padding: 16,
              boxShadow: '0 20px 48px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.08)',
            }}
          >
            <div style={{ marginBottom: 16 }}>
              <p style={{ fontSize: 13, fontWeight: 600, color: 'rgba(244,244,245,0.95)', letterSpacing: '-0.01em' }}>
                Send to…
              </p>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {/* Broadcast — all devices */}
              <motion.button
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: 0.06 }}
                onClick={() => handleDeviceSelect(null)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 12,
                  padding: '12px 12px',
                  backgroundColor: 'rgba(251,191,36,0.12)',
                  border: '1px solid rgba(251,191,36,0.2)',
                  borderRadius: 10, cursor: 'pointer', transition: 'all 0.2s ease',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.backgroundColor = 'rgba(251,191,36,0.18)';
                  e.currentTarget.style.borderColor = 'rgba(251,191,36,0.35)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.backgroundColor = 'rgba(251,191,36,0.12)';
                  e.currentTarget.style.borderColor = 'rgba(251,191,36,0.2)';
                }}
              >
                <Wifi size={16} style={{ color: '#fbbf24', flexShrink: 0 }} />
                <span style={{ fontSize: 13, fontWeight: 500, color: 'rgba(244,244,245,0.9)' }}>
                  All devices
                </span>
              </motion.button>

              {/* Individual devices */}
              {devices.map((device, index) => {
                const Icon = device.platform === 'android' ? Smartphone : Monitor;
                return (
                  <motion.button
                    key={device.id}
                    initial={{ opacity: 0, x: -10 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: (index + 2) * 0.06 }}
                    onClick={() => handleDeviceSelect(device.id)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 12,
                      padding: '12px 12px',
                      backgroundColor: 'rgba(255,255,255,0.04)',
                      border: '1px solid rgba(255,255,255,0.08)',
                      borderRadius: 10, cursor: 'pointer', transition: 'all 0.2s ease',
                    }}
                    onMouseEnter={(e) => {
                      e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.08)';
                      e.currentTarget.style.borderColor = 'rgba(255,255,255,0.15)';
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.backgroundColor = 'rgba(255,255,255,0.04)';
                      e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)';
                    }}
                  >
                    <Icon size={16} style={{ color: '#a1a1a6', flexShrink: 0 }} />
                    <div style={{ textAlign: 'left', minWidth: 0 }}>
                      <p style={{
                        fontSize: 13, fontWeight: 500, color: 'rgba(244,244,245,0.9)',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                      }}>
                        {device.label}
                      </p>
                      <p style={{ fontSize: 11, color: 'rgba(244,244,245,0.6)', marginTop: 2, textTransform: 'capitalize' }}>
                        {device.platform}
                      </p>
                    </div>
                  </motion.button>
                );
              })}
            </div>

            {uploadError && (
              <p style={{ fontSize: 11, color: '#f87171', marginTop: 8, wordBreak: 'break-word' }}>
                {uploadError}
              </p>
            )}
            <p style={{ fontSize: 11, color: 'rgba(244,244,245,0.5)', marginTop: 12, textAlign: 'center' }}>
              Press Esc to cancel
            </p>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="relative flex items-center justify-center">
        <AnimatePresence>
          {state === 'bloomed' && (
            <motion.div
              initial={{ opacity: 0, scale: 0.8 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
              transition={{ duration: 0.4 }}
              className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 pointer-events-none flex items-center justify-center"
            >
              <div className="absolute rounded-full animate-ping-slow shelfHalo" style={{ width: 188, height: 188, border: '1px solid rgba(251,191,36,0.3)', boxShadow: '0 0 24px rgba(251,191,36,0.25)' }} />
              <div className="absolute rounded-full animate-ping-slower shelfHalo shelfHaloOuter" style={{ width: 252, height: 252, border: '1px solid rgba(251,191,36,0.15)' }} />
            </motion.div>
          )}
        </AnimatePresence>

        <motion.div
          onMouseEnter={() => { if (stateRef.current === 'bloomed') setShelfState('ready'); }}
          onMouseLeave={() => {
            if (stateRef.current === 'ready') {
              setDraggedFileCount(0);
              setShelfState('bloomed');
            }
          }}
          initial={false}
          animate={{
            width: state === 'bloomed' ? 124 : state === 'hidden' ? 0 : state === 'picking' ? 0 : 300,
            height: state === 'bloomed' ? 124 : state === 'hidden' ? 0 : state === 'picking' ? 0 : 108,
            borderRadius: state === 'bloomed' || state === 'hidden' ? 999 : 28,
            opacity: visible && state !== 'picking' ? 1 : 0,
            scale: visible && state !== 'picking' ? 1 : 0.75,
          }}
          transition={{ type: 'spring', stiffness: 350, damping: 28, mass: 0.8 }}
          className="relative flex items-center justify-center pointer-events-auto shelfCore overflow-hidden"
          style={{ background: coreBackground, backdropFilter: 'blur(32px) saturate(1.2)', boxShadow: coreShadow, border: coreBorder }}
        >
          <div className="flex flex-col items-center justify-center gap-1.5 px-4 text-center shelfCopy z-10 w-full h-full">
            <AnimatePresence mode="wait">
              <motion.div
                key={state}
                initial={{ opacity: 0, y: 5 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -5 }}
                transition={{ duration: 0.2 }}
                className="flex flex-col items-center w-full justify-center h-full"
              >
                {state !== 'bloomed' && state !== 'accepted' && (
                  <span style={{
                    display: 'inline-flex', alignItems: 'center', gap: 8,
                    borderRadius: 999, border: '1px solid rgba(251,191,36,0.4)',
                    background: 'rgba(251,191,36,0.12)', padding: '4px 10px', marginBottom: 4,
                    fontSize: 10, fontWeight: 700, textTransform: 'uppercase',
                    letterSpacing: '0.26em', color: '#fbbf24', boxShadow: '0 0 12px rgba(251,191,36,0.2)',
                  }}>
                    {hasDraggedFiles ? `${draggedFileCount} file${draggedFileCount > 1 ? 's' : ''}` : 'File shelf'}
                  </span>
                )}

                {state === 'accepted' ? (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 14, color: '#f4f4f5' }}>
                    <span style={{
                      display: 'grid', height: 40, width: 40, placeItems: 'center',
                      borderRadius: '50%', background: 'rgba(244,244,245,0.16)', fontSize: 20, lineHeight: 1,
                      boxShadow: '0 0 0 1px rgba(255,255,255,0.15)', backdropFilter: 'blur(8px)',
                    }}>
                      <svg width="20" height="20" viewBox="0 0 16 16" fill="none">
                        <path d="M3 8l3.5 3.5L13 4.5" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"/>
                      </svg>
                    </span>
                    <div style={{ textAlign: 'left' }}>
                      <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: '-0.01em' }}>{shelfTitle}</div>
                      <div style={{ fontSize: 12, color: 'rgba(244,244,245,0.78)', fontWeight: 500, marginTop: 2 }}>{shelfSubtitle}</div>
                    </div>
                  </div>
                ) : (
                  <>
                    <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: '-0.01em', color: '#f4f4f5' }}>{shelfTitle}</div>
                    <div style={{ fontSize: 12, fontWeight: 500, color: 'rgba(244,244,245,0.66)', marginTop: 2 }}>{shelfSubtitle}</div>
                    {state === 'ready' && (
                      <div style={{ marginTop: 10, height: 6, width: 112, overflow: 'hidden', borderRadius: 999, background: 'rgba(244,244,245,0.1)', boxShadow: 'inset 0 1px 2px rgba(0,0,0,0.2)' }}>
                        <div className="animate-shelf-track" style={{ height: '100%', width: '50%', borderRadius: 999, background: 'linear-gradient(90deg, #fbbf24, #fff, #fbbf24)' }} />
                      </div>
                    )}
                  </>
                )}
              </motion.div>
            </AnimatePresence>
          </div>
        </motion.div>
      </div>
    </div>
  );
}
