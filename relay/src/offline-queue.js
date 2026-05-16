const { LIMITS } = require('./protocol');

class OfflineQueue {
  constructor() {
    // Map<deviceId, Array<{message, timestamp}>>
    this.queues = new Map();
    this.MAX_ITEMS = LIMITS.OFFLINE_QUEUE_MAX_PER_DEVICE;
    this.MAX_AGE_MS = LIMITS.OFFLINE_QUEUE_TTL_MS;

    // Start cleanup interval (every minute is fine even with 7-day TTL).
    setInterval(() => this.cleanup(), 60000);
  }

  enqueue(deviceId, message) {
    if (!this.queues.has(deviceId)) {
      this.queues.set(deviceId, []);
    }
    const queue = this.queues.get(deviceId);
    
    queue.push({
      message,
      timestamp: Date.now()
    });

    // Cap at MAX_ITEMS
    if (queue.length > this.MAX_ITEMS) {
      queue.shift(); // Remove oldest
    }
    
    console.log(`[OfflineQueue] Enqueued message for ${deviceId}. Queue size: ${queue.length}`);
  }

  flush(deviceId, ws) {
    const queue = this.queues.get(deviceId);
    if (!queue || queue.length === 0) return;

    const now = Date.now();
    let flushedCount = 0;

    for (const item of queue) {
      if (now - item.timestamp <= this.MAX_AGE_MS) {
        if (ws.readyState === 1 /* OPEN */) {
          ws.send(JSON.stringify(item.message));
          flushedCount++;
        }
      }
    }

    // Clear queue after flushing
    this.queues.delete(deviceId);
    console.log(`[OfflineQueue] Flushed ${flushedCount} messages to ${deviceId}`);
  }

  cleanup() {
    const now = Date.now();
    let expiredCount = 0;

    for (const [deviceId, queue] of this.queues.entries()) {
      const validItems = queue.filter(item => now - item.timestamp <= this.MAX_AGE_MS);
      if (validItems.length === 0) {
        this.queues.delete(deviceId);
      } else {
        expiredCount += (queue.length - validItems.length);
        this.queues.set(deviceId, validItems);
      }
    }

    if (expiredCount > 0) {
      console.log(`[OfflineQueue] Cleaned up ${expiredCount} expired messages`);
    }
  }
}

module.exports = new OfflineQueue();
