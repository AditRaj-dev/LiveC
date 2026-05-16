const WebSocket = require('ws');
const http = require('http');

console.log('Testing Relay Server...');

let serverProc;

async function runTests() {
  const ws1 = new WebSocket('ws://localhost:3000/ws');
  
  await new Promise(resolve => ws1.on('open', resolve));
  console.log('WS1 connected');

  // Join room
  ws1.send(JSON.stringify({
    type: 'device_join',
    payload: {
      roomToken: 'test-room',
      deviceId: 'dev1'
    }
  }));

  const ws2 = new WebSocket('ws://localhost:3000/ws');
  await new Promise(resolve => ws2.on('open', resolve));
  console.log('WS2 connected');

  // Listen for device_join broadcast
  const ws1MessagePromise = new Promise(resolve => {
    ws1.once('message', (msg) => {
      resolve(JSON.parse(msg.toString()));
    });
  });

  ws2.send(JSON.stringify({
    type: 'device_join',
    payload: {
      roomToken: 'test-room',
      deviceId: 'dev2'
    }
  }));

  const msg1 = await ws1MessagePromise;
  console.log('WS1 received:', msg1.type);
  if (msg1.type !== 'device_join' || msg1.from !== 'dev2') throw new Error('Failed to broadcast join');

  // Send message from dev2 to dev1
  const ws1MsgPromise2 = new Promise(resolve => {
    ws1.once('message', (msg) => {
      resolve(JSON.parse(msg.toString()));
    });
  });

  ws2.send(JSON.stringify({
    type: 'hello',
    from: 'dev2',
    to: 'dev1',
    payload: { text: 'hi' }
  }));

  const msg2 = await ws1MsgPromise2;
  console.log('WS1 received:', msg2.type);
  if (msg2.payload.text !== 'hi') throw new Error('Failed targeted delivery');

  // Test offline queue
  ws2.send(JSON.stringify({
    type: 'offline_msg',
    from: 'dev2',
    to: 'dev3', // not connected
    payload: { info: 'stored' }
  }));

  await new Promise(r => setTimeout(r, 100)); // allow time to queue

  const ws3 = new WebSocket('ws://localhost:3000/ws');
  await new Promise(resolve => ws3.on('open', resolve));
  console.log('WS3 connected');

  const ws3MsgPromise = new Promise(resolve => {
    ws3.once('message', (msg) => {
      resolve(JSON.parse(msg.toString()));
    });
  });

  ws3.send(JSON.stringify({
    type: 'device_join',
    payload: {
      roomToken: 'test-room',
      deviceId: 'dev3'
    }
  }));

  const msg3 = await ws3MsgPromise;
  console.log('WS3 received:', msg3.type);
  if (msg3.type !== 'offline_msg') throw new Error('Failed offline queue');

  console.log('All tests passed!');
  ws1.close();
  ws2.close();
  ws3.close();
  process.exit(0);
}

runTests().catch(e => {
  console.error(e);
  process.exit(1);
});
