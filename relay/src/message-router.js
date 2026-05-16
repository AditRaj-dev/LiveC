const roomManager = require('./room-manager');
const offlineQueue = require('./offline-queue');
const { BROADCAST } = require('./protocol');

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
