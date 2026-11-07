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
    else{res.statusCode=404;res.end();}
  });
  await new Promise(resolve=>server.listen(9321,'0.0.0.0',resolve));
};
