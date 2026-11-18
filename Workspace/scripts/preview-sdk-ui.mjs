import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve,sep,extname} from 'node:path';
const root=fileURLToPath(new URL('../channel-web-sdk/',import.meta.url));
const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8'};
createServer(async(req,res)=>{const path=resolve(root,'.'+decodeURIComponent(new URL(req.url,'http://localhost').pathname));if(!path.startsWith(root.endsWith(sep)?root:root+sep)||!types[extname(path)]){res.writeHead(404);res.end();return;}try{const content=await readFile(path);res.writeHead(200,{'Content-Type':types[extname(path)],'Cache-Control':'no-store'});res.end(content);}catch{res.writeHead(404);res.end();}}).listen(5186,'127.0.0.1',()=>console.log('UI preview: http://127.0.0.1:5186/previews/transaction-dialog.html'));
