// Port of packages/socket.io-client/test/support/server.ts at the pinned
// reference aaf2af36ec8ad05910f357a788e0e358bad32738, the server the original
// client suite runs against. Differences: it listens on an ephemeral port and
// prints `READY port=… secret=…`; `expect()` calls become guarded emits so a
// failed expectation shows up as a missing acknowledgement on the client; a
// few admin routes let tests drive the server.
import { Server } from "socket.io";
import http from "node:http";
import crypto from "node:crypto";

const SECRET = crypto.randomBytes(16).toString("hex");
const httpServer = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://127.0.0.1");
  if (!url.pathname.startsWith("/admin/") || req.headers["x-admin-secret"] !== SECRET) {
    res.writeHead(404).end();
    return;
  }
  if (url.pathname === "/admin/kill-transport") {
    const nsp = url.searchParams.get("nsp") ?? "/";
    const socket = server.of(nsp).sockets.get(url.searchParams.get("sid"));
    if (!socket) { res.writeHead(404).end("no sid"); return; }
    socket.conn.close();
    res.writeHead(200).end("killed");
    return;
  }
  if (url.pathname === "/admin/disconnect") {
    const nsp = url.searchParams.get("nsp") ?? "/";
    const socket = server.of(nsp).sockets.get(url.searchParams.get("sid"));
    if (!socket) { res.writeHead(404).end("no sid"); return; }
    socket.disconnect(url.searchParams.get("close") === "true");
    res.writeHead(200).end("ok");
    return;
  }
  if (url.pathname === "/admin/emit") {
    const nsp = url.searchParams.get("nsp") ?? "/";
    server.of(nsp).emit(url.searchParams.get("event") ?? "msg", ...JSON.parse(url.searchParams.get("args") ?? "[]"));
    res.writeHead(200).end("ok");
    return;
  }
  if (url.pathname === "/admin/stats") {
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
      engines: server.engine.clientsCount,
      sockets: server.of("/").sockets.size,
    }));
    return;
  }
  res.writeHead(404).end("no route");
});

const server = new Server(httpServer, {
  pingInterval: 2000,
  connectionStateRecovery: {},
  allowRequest: (req, callback) => {
    // add a fixed delay to test the connection timeout on the client side
    setTimeout(() => callback(null, true), 10);
  },
});

server.of("/foo").on("connection", (socket) => {
  socket.on("getId", (cb) => {
    cb(socket.id);
  });
});

server.of("/timeout_socket").on("connection", () => {
  // register namespace
});

server.of("/valid").on("connection", () => {
  // register namespace
});

server.of("/asd").on("connection", () => {
  // register namespace
});

server.of("/abc").on("connection", (socket) => {
  socket.emit("handshake", socket.handshake);
});

server.use((socket, next) => {
  if (socket.request._query.fail)
    return next(new Error("Auth failed (main namespace)"));
  next();
});

server.of("/no").use((socket, next) => {
  next(new Error("Auth failed (custom namespace)"));
});

server.of("/with-data").use((socket, next) => {
  const err = new Error("Auth failed (with data)");
  err.data = { code: 401, details: "Invalid token" };
  next(err);
});

server.on("connection", (socket) => {
  // simple test
  socket.on("hi", () => {
    socket.emit("hi");
  });

  socket.on("echo", (arg, cb) => {
    cb(arg);
  });

  // ack tests
  socket.on("ack", () => {
    socket.emit("ack", (a, b) => {
      if (a === 5 && b.test) {
        socket.emit("got it");
      }
    });
  });

  socket.on("getAckDate", (data, cb) => {
    cb(new Date());
  });

  socket.on("getDate", () => {
    socket.emit("takeDate", new Date());
  });

  socket.on("getDateObj", () => {
    socket.emit("takeDateObj", { date: new Date() });
  });

  socket.on("getUtf8", () => {
    socket.emit("takeUtf8", "てすと");
    socket.emit("takeUtf8", "Я Б Г Д Ж Й");
    socket.emit("takeUtf8", "Ä ä Ü ü ß");
    socket.emit("takeUtf8", "utf8 — string");
    socket.emit("takeUtf8", "utf8 — string");
  });

  // false test
  socket.on("false", () => {
    socket.emit("false", false);
  });

  // binary test
  socket.on("doge", () => {
    const buf = Buffer.from("asdfasdf", "utf8");
    socket.emit("doge", buf);
  });

  // expect receiving binary to be buffer
  socket.on("buffa", (a) => {
    if (Buffer.isBuffer(a)) socket.emit("buffack");
  });

  // expect receiving binary with mixed JSON
  socket.on("jsonbuff", (a) => {
    if (a.hello === "lol" && Buffer.isBuffer(a.message) && a.goodbye === "gotcha") {
      socket.emit("jsonbuff-ack");
    }
  });

  // expect receiving buffers in order
  let receivedAbuff1 = false;
  socket.on("abuff1", (a) => {
    if (Buffer.isBuffer(a)) receivedAbuff1 = true;
  });
  socket.on("abuff2", (a) => {
    if (receivedAbuff1) socket.emit("abuff2-ack");
  });

  // expect sent blob to be buffer
  socket.on("blob", (a) => {
    if (Buffer.isBuffer(a)) socket.emit("back");
  });

  // expect sent blob mixed with json to be buffer
  socket.on("jsonblob", (a) => {
    if (a.hello === "lol" && Buffer.isBuffer(a.message) && a.goodbye === "gotcha") {
      socket.emit("jsonblob-ack");
    }
  });

  // expect blobs sent in order to arrive in correct order
  let receivedblob1 = false;
  let receivedblob2 = false;
  socket.on("blob1", (a) => {
    if (Buffer.isBuffer(a)) receivedblob1 = true;
  });
  socket.on("blob2", (a) => {
    if (receivedblob1 && a === "second") receivedblob2 = true;
  });
  socket.on("blob3", (a) => {
    if (Buffer.isBuffer(a) && receivedblob1 && receivedblob2) socket.emit("blob3-ack");
  });

  // emit buffer to base64 receiving browsers
  socket.on("getbin", () => {
    const buf = Buffer.from("asdfasdf", "utf8");
    socket.emit("takebin", buf);
  });

  socket.on("getHandshake", (cb) => {
    cb(socket.handshake);
  });

  socket.on("getId", (cb) => {
    cb(socket.id);
  });
});

httpServer.listen(0, "127.0.0.1", () => {
  console.log(`READY port=${httpServer.address().port} secret=${SECRET}`);
});
process.on("SIGTERM", () => server.close(() => process.exit(0)));
