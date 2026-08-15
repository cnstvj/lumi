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
          rooms.set(currentRoom, []);
        }
        const peers = rooms.get(currentRoom);
        peers.push(ws);

        const isHost = peers.length === 1;
        ws.send(JSON.stringify({ action: 'host_status', is_host: isHost }));
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
    }
  });

  ws.on('close', () => {
    if (currentRoom && rooms.has(currentRoom)) {
      const peers = rooms.get(currentRoom);
      const index = peers.indexOf(ws);
      if (index !== -1) {
        peers.splice(index, 1);
        
        if (index === 0 && peers.length > 0) {
          const newHost = peers[0];
          if (newHost.readyState === newHost.OPEN) {
            newHost.send(JSON.stringify({ action: 'host_status', is_host: true }));
          }
        }
      }

      if (peers.length === 0) {
        rooms.delete(currentRoom);
      }
    }
  });
});

const PORT = process.env.PORT || 4000;
server.listen(PORT);
