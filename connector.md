# Lumi Coordinator Specification

This document defines the queue-based room and host management strategy utilized by the Lumi WebSocket Coordinator (`coordinator/server.js`).

## Room & Host Lifecycle Management

### 1. Connection Initialization
* The coordinator maintains a dynamic, memory-resident registry of active rooms using a `Map` of connection queues (arrays):
  ```javascript
  const rooms = new Map(); // Key: room_number (String), Value: peers (Array of WebSockets)
  ```
* No persistent or static room data is stored on disk.

### 2. Joining a Room (Enqueue)
* When a device joins a room, it sends a handshake payload specifying the target `room` number.
* If the room queue does not exist, the coordinator instantiates it and marks the first enqueued device as the **Host**:
  ```javascript
  if (!rooms.has(roomCode)) {
    rooms.set(roomCode, []);
  }
  const peers = rooms.get(roomCode);
  peers.push(ws); // Enqueue device
  ```
* **Host Election**: The device at index `0` of the queue (`peers[0]`) is elected as the Host. The coordinator immediately replies with:
  ```json
  { "action": "host_status", "is_host": true }
  ```
* Any subsequent devices enqueued in the same room are designated as **Followers** and receive:
  ```json
  { "action": "host_status", "is_host": false }
  ```

### 3. Leaving a Room (Dequeue / Host Promotion)
* When a device disconnects (triggers `close` event):
  1. The device is removed (dequeued) from the room queue.
  2. If the departing device was the **Host** (index `0`) and other devices remain in the queue:
     * The next device in the queue (`peers[0]`) is promoted to **Host**.
     * The coordinator sends a notification to promote the new host:
       ```json
       { "action": "host_status", "is_host": true }
       ```
  3. If no devices remain in the queue (the room is empty), the room mapping is completely deleted from memory:
     ```javascript
     if (peers.length === 0) {
       rooms.delete(currentRoom);
     }
     ```