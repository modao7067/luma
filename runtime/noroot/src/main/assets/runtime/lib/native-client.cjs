const net = require('node:net');
const fs = require('node:fs');
const path = require('node:path');
const command = path.basename(process.argv[1]);
const socketName = process.env.LUMA_NATIVE_SOCKET || fs.readFileSync('/opt/luma/socket-name', 'utf8').trim();
const aliases = {'luma-compute-check':'compute-check', 'android-run':'android-run', 'adb-shell':'adb-shell', 'shizuku':'adb-shell', 'rish':'adb-shell'};
const socket = net.createConnection({path:'\0'+socketName});
let pending = '', exited = false;
function send(value) { if(!socket.destroyed)socket.write(JSON.stringify(value)+'\n'); }
socket.on('connect', () => {
  send({command:aliases[command]||command,args:process.argv.slice(2),cwd:process.cwd()});
  if(!process.stdin.isTTY) {
    process.stdin.on('data', data => send({stdin:data.toString('base64')}));
    process.stdin.on('end', () => send({eof:true}));
  }
});
socket.setEncoding('utf8');
socket.on('data', data => {
  pending += data;
  let at;
  while((at=pending.indexOf('\n'))>=0) {
    const line=pending.slice(0,at);pending=pending.slice(at+1);
    try {
      const event=JSON.parse(line);
      if(event.stdout)process.stdout.write(Buffer.from(event.stdout,'base64'));
      if(event.stderr)process.stderr.write(Buffer.from(event.stderr,'base64'));
      if(event.error)process.stderr.write(event.error+'\n');
      if(event.exit!==undefined){exited=true;process.exitCode=event.exit;socket.end();process.stdin.pause();}
    } catch(error) { process.stderr.write('Invalid native response: '+error.message+'\n');socket.destroy();process.exitCode=1; }
  }
});
socket.on('error', error => {process.stderr.write('Luma native: '+error.message+'\n');process.exitCode=1;process.stdin.pause();});
socket.on('close', () => {if(!exited)process.exitCode=1;process.stdin.pause();});
process.on('SIGINT', () => send({signal:'INT'}));
process.on('SIGTERM', () => {send({signal:'TERM'});socket.end();});
