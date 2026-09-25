const http = require('http');
const https = require('https');

const port = Number(process.env.PORT || 3000);
const branch = process.env.PREVIEW_BRANCH || 'preview-m1-3-nearby-intelligence';
const base = `https://raw.githubusercontent.com/9dbit/Telemetry/${branch}/preview/nearby/`;
const files = new Map([
  ['/', 'index.html'],
  ['/index.html', 'index.html'],
  ['/styles.css', 'styles.css'],
  ['/app.js', 'app.js']
]);
const types = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8'
};

function typeFor(file) {
  if (file.endsWith('.css')) return types['.css'];
  if (file.endsWith('.js')) return types['.js'];
  return types['.html'];
}

function fetchFile(file, res) {
  const req = https.get(base + file, {
    headers: {
      'user-agent': 'TelemetryPreviewHost/1.0',
      'accept': '*/*'
    }
  }, upstream => {
    if (upstream.statusCode !== 200) {
      res.writeHead(upstream.statusCode || 502, {'content-type':'text/plain; charset=utf-8','cache-control':'no-store'});
      upstream.resume();
      return res.end(`Preview source unavailable (${upstream.statusCode || 'unknown'})`);
    }
    res.writeHead(200, {
      'content-type': typeFor(file),
      'cache-control': 'no-store',
      'x-telemetry-preview-branch': branch
    });
    upstream.pipe(res);
  });
  req.on('error', err => {
    res.writeHead(502, {'content-type':'text/plain; charset=utf-8','cache-control':'no-store'});
    res.end(`Preview upstream error: ${err.message}`);
  });
  req.setTimeout(8000, () => req.destroy(new Error('upstream timeout')));
}

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/health') {
    res.writeHead(200, {'content-type':'application/json','cache-control':'no-store'});
    return res.end(JSON.stringify({ok:true,service:'telemetry-preview-host',branch}));
  }
  const file = files.get(url.pathname);
  if (!file) {
    res.writeHead(404, {'content-type':'text/plain; charset=utf-8'});
    return res.end('Not found');
  }
  fetchFile(file, res);
}).listen(port, '0.0.0.0', () => {
  console.log(`Telemetry preview host listening on :${port} using ${branch}`);
});
