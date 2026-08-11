const http = require('http');
const path = require('path');
const { spawn } = require('child_process');
const { Readable } = require('stream');

test('uploads a 5 MiB image through the multipart API', async () => {
  let uploadedBytes = 0;
  let origin;

  const server = http.createServer(async (request, response) => {
    try {
      response.setHeader('Content-Type', 'application/json');

      if (request.method === 'POST' && request.url === '/api/auth/register') {
        response.statusCode = 201;
        response.end('{}');
      } else if (request.method === 'POST' && request.url === '/api/auth/login') {
        response.end(JSON.stringify({ token: 'token', sessionId: 'session' }));
      } else if (request.method === 'POST' && request.url === '/api/files/upload') {
        const form = await new Request(`${origin}${request.url}`, {
          method: request.method,
          headers: { 'Content-Type': request.headers['content-type'] },
          body: Readable.toWeb(request),
          duplex: 'half'
        }).formData();
        const file = form.get('file');
        uploadedBytes = file.size;
        response.end(JSON.stringify({ success: true, file: { _id: 'file-1' } }));
      } else if (request.method === 'DELETE' && request.url === '/api/files/file-1') {
        response.end(JSON.stringify({ success: true }));
      } else {
        response.statusCode = 404;
        response.end('{}');
      }
    } catch (error) {
      response.statusCode = 500;
      response.end(JSON.stringify({ error: error.message }));
    }
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;

  const child = spawn(process.execPath, [
    path.join(__dirname, '../file-upload-api-load.js'),
    `--api-url=${origin}`,
    '--users=1',
    '--concurrency=1'
  ], { env: { ...process.env, FORCE_COLOR: '0' } });

  let output = '';
  child.stdout.on('data', (chunk) => { output += chunk; });
  child.stderr.on('data', (chunk) => { output += chunk; });
  const exitCode = await new Promise((resolve) => child.on('close', resolve));
  await new Promise((resolve) => server.close(resolve));

  expect(exitCode).toBe(0);
  expect(uploadedBytes).toBe(5 * 1024 * 1024);
  expect(output).toContain('Completed    : 1/1');
}, 20000);
