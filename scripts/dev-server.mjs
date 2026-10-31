import { createServer } from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { dirname, extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
const project = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const web = resolve(project, 'web');
const port = Number(process.env.PORT || 4173);
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.glb': 'model/gltf-binary', '.svg': 'image/svg+xml' };
const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const root = pathname.startsWith('/cards/') ? resolve(project, 'cards') : web;
    const relative = pathname.startsWith('/cards/') ? pathname.slice(7) : pathname.slice(1);
    let path = resolve(root, relative || 'index.html');
    if (path !== root && !path.startsWith(root + sep)) { response.writeHead(403); response.end(); return; }
    let metadata = await stat(path);
    if (metadata.isDirectory()) { path = resolve(path, 'index.html'); metadata = await stat(path); }
    response.writeHead(200, { 'Content-Type': types[extname(path).toLowerCase()] || 'application/octet-stream', 'Content-Length': metadata.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    if (request.method === 'HEAD') response.end(); else createReadStream(path).pipe(response);
  } catch { response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }); response.end('资源未找到'); }
});
server.listen(port, '127.0.0.1', () => console.log(`映光卡匣: http://127.0.0.1:${port}/`));
