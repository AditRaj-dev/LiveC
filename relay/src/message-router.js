const roomManager = require('./room-manager');
const offlineQueue = require('./offline-queue');
const transferManager = require('./transfer-manager');
const { BROADCAST, MESSAGE_TYPES } = require('./protocol');

class MessageRouter {
  handleMessage(ws, messageStr) {
    let messageObj;
    try {
      messageObj = JSON.parse(messageStr);
    } catch (e) {
      console.warn('[MessageRouter] Received malformed JSON');
      return;
    }

    const { type, from, to, payload } = messageObj;
    if (!type || !from || !to) {
      console.warn('[MessageRouter] Message missing required fields');
      return;
    }

    const roomToken = ws.roomToken;
    if (!roomToken) {
      console.warn('[MessageRouter] Received message from unauthenticated socket');
      return;
    }

    console.log(`[MessageRouter] Routing ${type} from ${from.slice(0,8)} to ${to}`);

    // ── Two-phase transfer intercepts ────────────────────────────────────────

    if (type === MESSAGE_TYPES.FILE_OFFER) {
      // Register offer in state machine before routing so accept can look it up.
      const { offerId, files } = payload || {};
      if (offerId && Array.isArray(files)) {
        transferManager.registerOffer(offerId, from, to, files, roomToken);
      }
      // Fall through to normal routing.

    } else if (type === MESSAGE_TYPES.FILE_ACCEPT) {
      // Enrich payload with per-file upload tokens before forwarding to sender.
      const { offerId, fileIds } = payload || {};
      if (offerId && Array.isArray(fileIds)) {
        const uploadTokens = {};
        for (const fileId of fileIds) {
          const result = transferManager.acceptFile(offerId, fileId, from);
          if (result) uploadTokens[fileId] = result.uploadToken;
        }
        messageObj = { ...messageObj, payload: { ...payload, uploadTokens } };
        messageStr = JSON.stringify(messageObj);
      }
      // Fall through to route enriched message to sender.

    } else if (type === MESSAGE_TYPES.FILE_REJECT) {
      const { offerId } = payload || {};
      if (offerId) transferManager.rejectOffer(offerId);
      // Fall through to route to sender.

    } else if (type === MESSAGE_TYPES.FILE_DONE) {
      // Recipient confirmed download — delete file, no need to route.
      const { offerId, fileId } = payload || {};
      if (offerId && fileId) transferManager.markDone(offerId, fileId);
      return;
    }

    // ── Standard routing ─────────────────────────────────────────────────────

    if (to === BROADCAST) {
      roomManager.broadcast(roomToken, from, messageObj);
    } else {
      const targetSocket = roomManager.getDevice(roomToken, to);
      if (targetSocket && targetSocket.readyState === 1 /* OPEN */) {
        targetSocket.send(messageStr);
        console.log(`[MessageRouter] Targeted ${type} → ${to.slice(0,8)} SENT`);
      } else {
        offlineQueue.enqueue(to, messageObj);
        console.log(`[MessageRouter] Targeted ${type} → ${to.slice(0,8)} QUEUED (offline/missing, state=${targetSocket?.readyState ?? 'no socket'})`);
      }
    }
  }
}

module.exports = new MessageRouter();
