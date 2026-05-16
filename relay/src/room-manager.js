const { randomUUID } = require('crypto');
const { MESSAGE_TYPES, BROADCAST } = require('./protocol');

class RoomManager {
  constructor() {
    // Map<roomToken, Map<deviceId, WebSocket>>
    this.rooms = new Map();
  }

  join(ws, roomToken, deviceId, deviceName, platform) {
    if (!this.rooms.has(roomToken)) {
      this.rooms.set(roomToken, new Map());
    }
    const room = this.rooms.get(roomToken);

    // Save metadata on the socket for quick access
    ws.roomToken = roomToken;
    ws.deviceId = deviceId;
    ws.deviceName = deviceName || deviceId;
    ws.platform = platform || 'unknown';

    // Tell the new joiner about everyone already in the room
    for (const [existingId, existingWs] of room.entries()) {
      if (existingWs.readyState === 1 /* OPEN */) {
        ws.send(JSON.stringify({
          type: MESSAGE_TYPES.DEVICE_JOIN,
          id:   randomUUID(),
          from: existingId,
          to:   deviceId,
          room: roomToken,
          payload: {
            deviceId:   existingId,
            deviceName: existingWs.deviceName,
            platform:   existingWs.platform,
            timestamp:  Date.now(),
          },
        }));
      }
    }

    room.set(deviceId, ws);

    // Broadcast device_join to others in the room
    this.broadcast(roomToken, deviceId, {
      type: MESSAGE_TYPES.DEVICE_JOIN,
      id:   randomUUID(),
      from: deviceId,
      to:   BROADCAST,
      room: roomToken,
      payload: {
        deviceId,
        deviceName: ws.deviceName,
        platform:   ws.platform,
        timestamp:  Date.now(),
      }
    });

    console.log(`[RoomManager] Device ${deviceId} joined room ${roomToken}`);
  }

  leave(ws) {
    const { roomToken, deviceId } = ws;
    if (!roomToken || !deviceId) return;

    const room = this.rooms.get(roomToken);
    if (room) {
      room.delete(deviceId);
      if (room.size === 0) {
        this.rooms.delete(roomToken);
      } else {
        // Broadcast device_leave to others
        this.broadcast(roomToken, deviceId, {
          type: MESSAGE_TYPES.DEVICE_LEAVE,
          id:   randomUUID(),
          from: deviceId,
          to:   BROADCAST,
          room: roomToken,
          payload: {
            deviceId,
            timestamp: Date.now(),
          }
        });
      }
    }
    console.log(`[RoomManager] Device ${deviceId} left room ${roomToken}`);
  }

  getRoom(roomToken) {
    return this.rooms.get(roomToken);
  }

  getDevice(roomToken, deviceId) {
    const room = this.rooms.get(roomToken);
    return room ? room.get(deviceId) : null;
  }

  broadcast(roomToken, senderDeviceId, messageObj) {
    const room = this.rooms.get(roomToken);
    if (!room) return;

    const messageStr = JSON.stringify(messageObj);
    let sent = 0;
    for (const [id, socket] of room.entries()) {
      if (id !== senderDeviceId && socket.readyState === 1 /* OPEN */) {
        socket.send(messageStr);
        sent++;
      }
    }
    console.log(`[RoomManager] broadcast ${messageObj.type} → ${sent} recipient(s) in room ${roomToken} (room size: ${room.size})`);
  }
}

module.exports = new RoomManager();
