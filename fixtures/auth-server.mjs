// Kotlin-specific fixture (KT contracts): a Socket.IO server whose middleware
// refuses revoked tokens, for token renewal across reconnects. Admin routes:
// /admin/revoke?token=… and /admin/kill?sid=… (drop the transport).
import http from "node:http";
import crypto from "node:crypto";
import { Server } from "socket.io";

const SECRET = crypto.randomBytes(16).toString("hex");
const revoked = new Set();
const seen = [];
const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://127.0.0.1");
  if (req.headers["x-admin-secret"] !== SECRET) { res.writeHead(404).end(); return; }
  if (url.pathname === "/admin/revoke") {
    revoked.add(url.searchParams.get("token"));
    res.writeHead(200).end("ok");
    return;
  }
  if (url.pathname === "/admin/kill") {
    const socket = io.of("/").sockets.get(url.searchParams.get("sid"));
    if (!socket) { res.writeHead(404).end("no sid"); return; }
    socket.conn.close();
    res.writeHead(200).end("ok");
    return;
  }
  if (url.pathname === "/admin/seen") {
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(seen));
    return;
  }
  res.writeHead(404).end();
});
const io = new Server(httpServer, { pingInterval: Number(process.env.PING_INTERVAL || 25000), pingTimeout: Number(process.env.PING_TIMEOUT || 20000) });
io.use((socket, next) => {
  const token = socket.handshake.auth?.token;
  seen.push(token ?? null);
  if (!token || revoked.has(token)) {
    const err = new Error("token rejected");
    err.data = { token: token ?? null };
    return next(err);
  }
  next();
});
io.on("connection", (socket) => {
  socket.on("whoami", (cb) => cb(socket.handshake.auth?.token ?? null));
  socket.on("echo", (value, cb) => cb(value));
  socket.on("slow-echo", (value, cb) => setTimeout(() => cb(value), Number(process.env.SLOW_MS || 300)));
  socket.on("count", (value) => socket.emit("counted", value));
});
httpServer.listen(0, "127.0.0.1", () => {
  console.log(`READY port=${httpServer.address().port} secret=${SECRET}`);
});
process.on("SIGTERM", () => io.close(() => process.exit(0)));
