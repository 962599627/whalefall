// Synthetic fixture. Never bundled into the production application.
const fs=require('node:fs'),path=require('node:path');
exports.start=async()=>{
  const file=path.join(process.env.NODE_PATH,'probe-starts.txt');
  const starts=Number(fs.existsSync(file)?fs.readFileSync(file,'utf8'):0)+1;
  fs.writeFileSync(file,String(starts));
  const server=catServerFactory(async(req,res)=>{
    const base='http://127.0.0.1:'+server.address().port;
    if(req.url==='/t4'){
      res.setHeader('Content-Type','application/json');
      res.end(JSON.stringify({starts,sites:[{key:'fixture',name:'Fixture',type:4,api:base+'/api/demo'}]}));
    }else if(req.url==='/api/fetch'){
      const response=await fetch(base+'/api/bytes');res.end(Buffer.from(await response.arrayBuffer()));
    }else if(req.url==='/api/bytes'){res.end(Buffer.from([0,1,255,42]));}
    else if(req.url==='/api/play'){
      res.setHeader('Content-Type','application/json');
      res.end(JSON.stringify({url:base+'/spider/demo/3/proxy/token/hls/index.m3u8?sig=fixture'}));
    }else if(req.url==='/spider/demo/3/proxy/token/hls/index.m3u8?sig=fixture'){
      res.setHeader('Content-Type','application/vnd.apple.mpegurl');
      res.end('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="../key?sig=fixture"\n../key?sig=fixture\n');
    }else if(req.url==='/spider/demo/3/proxy/token/key?sig=fixture'){
      res.setHeader('Content-Type','application/octet-stream');res.end(Buffer.from([0,1,255,42]));
    }
    else{res.statusCode=404;res.end();}
  });
  await new Promise(resolve=>server.listen(9321,'0.0.0.0',resolve));
};
