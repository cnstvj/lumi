const { WebSocketServer } = require('ws');
const http = require('http');

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('Lumi WebSocket Coordinator is running\n');
});

const wss = new WebSocketServer({ server });
const rooms = new Map();

wss.on('connection', (ws) => {
  let currentRoom = null;

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message.toString());

      if (data.action === 'ping') {
        ws.send(JSON.stringify({ action: 'pong', timestamp: data.timestamp }));
        return;
      }

      if (data.action === 'join' && data.room) {
        currentRoom = data.room;
        if (!rooms.has(currentRoom)) {
          rooms.set(currentRoom, new Set());
        }
        rooms.get(currentRoom).add(ws);
        console.log(`Device joined room: ${currentRoom} (${rooms.get(currentRoom).size} peers)`);
        return;
      }

      if (currentRoom && rooms.has(currentRoom)) {
        const peers = rooms.get(currentRoom);
        for (const client of peers) {
          if (client !== ws && client.readyState === ws.OPEN) {
            client.send(message.toString());
          }
        }
      }
    } catch (e) {
      console.error('Error handling message:', e);
    }
  });

  ws.on('close', () => {
    if (currentRoom && rooms.has(currentRoom)) {
      rooms.get(currentRoom).delete(ws);
      console.log(`Device left room: ${currentRoom}`);
      if (rooms.get(currentRoom).size === 0) {
        rooms.delete(currentRoom);
      }
    }
  });
});

const PORT = process.env.PORT || 4000;
server.listen(PORT, () => {
  console.log(`Lumi WebSocket Coordinator listening on port ${PORT}`);
});
