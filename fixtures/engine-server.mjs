// Port of packages/engine.io-client/test/support/hooks.js at the pinned
// reference aaf2af36ec8ad05910f357a788e0e358bad32738: the engine.io server the
// original engine client suite runs against, without the browser bundle. It
// listens on an ephemeral port and prints `READY port=… secret=…`.
import http from "node:http";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
// The exact engine.io dependency of the locked socket.io fixture.
const { attach } = require(require.resolve("engine.io", { paths: [require.resolve("socket.io")] }));

const serialize = (name, value, options = {}) => {
  let cookie = `${name}=${value}`;
  if (options.maxAge !== undefined) cookie += `; Max-Age=${options.maxAge}`;
  if (options.expires) cookie += `; Expires=${options.expires.toUTCString()}`;
  if (options.path) cookie += `; Path=${options.path}`;
  if (options.httpOnly) cookie += "; HttpOnly";
  if (options.secure) cookie += "; Secure";
  if (options.sameSite) cookie += "; SameSite=Strict";
  return cookie;
};

const httpServer = http.createServer((req, res) => res.writeHead(404).end());
const engine = attach(httpServer, {
  pingInterval: Number(process.env.PING_INTERVAL || 500),
  maxHttpBufferSize: 100,
  allowRequest: (req, fn) => {
    const denyRequest = new URL(`http://${req.url}`).searchParams.has("deny");
    fn(null, !denyRequest);
  },
});

engine.on("connection", (socket) => {
  socket.send("hi");

  // Bounce any received messages back
  socket.on("message", (data) => {
    if (data === "give binary") {
      const abv = new Int8Array(5);
      for (let i = 0; i < 5; i++) {
        abv[i] = i;
      }
      socket.send(abv);
      return;
    } else if (data === "give utf8") {
      socket.send("пойду спать всем спокойной ночи");
      return;
    } else if (data === "sendHeaders") {
      const headers = socket.transport?.dataReq?.headers;
      return socket.send(JSON.stringify(headers));
    }

    socket.send(data);
  });
});

engine.on("initial_headers", (headers) => {
  headers["set-cookie"] = [
    serialize("1", "1", { maxAge: 86400 }),
    serialize("2", "2", {
      sameSite: true,
      path: "/",
      httpOnly: true,
      secure: true,
    }),
    serialize("3", "3", { maxAge: 0 }),
    serialize("4", "4", { expires: new Date() }),
  ];
});

httpServer.listen(0, "127.0.0.1", () => {
  console.log(`READY port=${httpServer.address().port} secret=0123456789abcdef`);
});
process.on("SIGTERM", () => { engine.close(); httpServer.close(() => process.exit(0)); });
