"""Auete adapter and exact-code literal reader; synthetic HTTP/DEX fixtures only."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import base64
import hashlib
import json
import subprocess
import tempfile
import threading
import unittest
import urllib.parse
import test_guard_bootstrap as guard

ROOT, NODE = guard.ROOT, guard.NODE

class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.server.paths.append(self.path)
        path = urllib.parse.urlsplit(self.path).path
        origin = self.server.origin
        if 'searchword=challenge' in self.path:
            body = '<form action="?scheckAC=check"><input name="validate"><img src="include/vdimgck.php"></form>'
        elif 'play-' in path:
            mode = int(path.rsplit('-',1)[1].split('.')[0])
            values = [
                'base64decode("'+base64.b64encode((origin+'/media.mp4?token=fixture').encode()).decode().rstrip('=')+'")',
                json.dumps(origin+'/plain.mp4'), '"relative/movie.m3u8?token=fixture"',
                '(globalThis.untrustedExecuted=true,"'+origin+'/unsafe.mp4")',
                '"file:///fixture.mp4"', json.dumps(origin.replace('://','://fixture:secret@')+'/media.mp4'),
            ]
            body = '<div><script>var now='+values[mode]+'; var ignored=1;</script></div>'
        elif path == '/Movie/dzp/alpha/':
            body = ('<div class="detail-main-card"><h1 class="detail-title">《Fixture》</h1>'
                    '<div class="detail-poster"><img src="/poster.png"></div></div>'
                    '<div class="message"><p><span class="detail-label">地区：</span><b>Fixture region</b></p>'
                    '<p><span class="detail-label">年代：</span><b>2025</b></p><p class="detail-des">Fixture plot</p></div>')
            for line in range(2):
                body += '<div class="play-card" id="player_list"><div class="card-header"><span><b>线路：Line'+str(line)+'</b></span></div><ul class="episode-list">'
                body += ''.join('<li><a href="/Movie/dzp/alpha/play-'+str(line)+'-'+str(i)+'.html">Episode'+str(i)+'</a></li>' for i in range(3))
                body += '</ul></div>'
        else:
            second = 'index2.html' in path
            ident = 'beta' if second else 'alpha'
            title = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get('searchword',['Fixture'])[0]
            body = '<ul class="navbar-nav mr-auto"><li><a href="/Movie/index.html">电影</a></li><li><a href="/Tv/index.html">电视剧</a></li></ul>'
            body += '<ul class="threadlist"><li><a class="pic"><img src="/poster.png"></a><h2><a title="'+title+'" href="/Movie/dzp/'+ident+'/">'+title+'</a></h2><span class="hdtag">Test</span></li></ul>'
        data = body.encode()
        self.send_response(200); self.send_header('Content-Type','text/html; charset=utf-8'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)

class AueteChecks(unittest.TestCase):
    setUpClass = classmethod(guard.HostChecks.setUpClass.__func__)
    tearDownClass = classmethod(guard.HostChecks.tearDownClass.__func__)
    request = guard.HostChecks.request

    def test_adapter_six_methods_filters_and_pagination(self):
        fixture = ThreadingHTTPServer(('127.0.0.1',0),Fixture)
        fixture.daemon_threads=True; fixture.origin='http://127.0.0.1:'+str(fixture.server_port);fixture.paths=[]
        threading.Thread(target=fixture.serve_forever,daemon=True).start()
        try:
            config={'origin':fixture.origin,'mediaBase':fixture.origin+'/cdn/','userAgent':'Fixture-UA'}
            js="console.log(JSON.stringify(require(process.argv[1])(JSON.parse(process.argv[2]))))"
            rule=json.loads(subprocess.check_output([NODE,'--jitless','-e',js,str(ROOT/'shared/catpaw/drpy-auete-adapter.cjs'),json.dumps(config)],stderr=subprocess.DEVNULL))
            manifest={'kind':'drpy','version':1,'runtime':'drpy2-ca23ffdb-1','sourceId':'e'*64,
                      'origin':fixture.origin+'/rule.js','rule':rule,'headers':{},'requestTimeoutMs':2000}
            data=json.dumps(manifest).encode(); digest=hashlib.sha256(data).hexdigest()
            (self.root/digest).mkdir(); (self.root/digest/'drpy.json').write_bytes(data)
            api='/'+self.state['secret']+'/'+digest+'/api/drpy'
            def call(method,args):
                status,value=self.request({'version':1,'method':method,'args':args},path=api)
                self.assertEqual(status,200,value);return value
            home=call('home',[True]);self.assertEqual(len(home['class']),2)
            self.assertEqual(home['filters']['Movie'][0]['value'][1],{'n':'喜剧片','v':'xjp'})
            recommended=call('homeVod',[]);self.assertEqual(recommended['list'][0]['vod_name'],'Fixture')
            category=call('category',['Movie',1,True,{'type':'xjp'}]);self.assertIn('/Movie/xjp/index.html',fixture.paths)
            page2=call('category',['Movie',2,True,{}]);self.assertIn('/Movie/index2.html',fixture.paths)
            self.assertNotEqual(category['list'][0]['vod_id'],page2['list'][0]['vod_id'])
            self.assertEqual(call('search',['Fixture search',False,2])['list'][0]['vod_name'],'Fixture search')
            status,verification=self.request({'version':1,'method':'search','args':['challenge',False,1]},path=api)
            self.assertEqual((status,verification),(422,{'error':'drpy_verification_required'}))
            detail=call('detail',[category['list'][0]['vod_id']])['list'][0]
            self.assertEqual(detail['vod_name'],'Fixture');self.assertEqual(detail['vod_year'],'2025')
            self.assertEqual(detail['vod_play_from'],'Line0$$$Line1')
            self.assertEqual(len(detail['vod_play_url'].split('$$$')),2)
            for mode,expected in [(0,'/media.mp4?token=fixture'),(1,'/plain.mp4'),(2,'/cdn/relative/movie.m3u8?token=fixture')]:
                play=call('play',['Line0',fixture.origin+'/Movie/dzp/alpha/play-0-'+str(mode)+'.html',[]])
                self.assertEqual(play['parse'],0);self.assertEqual(play['url'],fixture.origin+expected)
            for mode in [3,4,5]:
                status,value=self.request({'version':1,'method':'play','args':['Line0',fixture.origin+'/Movie/dzp/alpha/play-0-'+str(mode)+'.html',[]]},path=api)
                self.assertEqual(status,422);self.assertEqual(value['error'],'drpy_media_unresolved' if mode==3 else 'drpy_media_protocol')
                self.assertNotIn('secret',json.dumps(value))
            self.assertFalse(any('/unsafe.mp4' in path for path in fixture.paths))
        finally:
            fixture.shutdown();fixture.server_close()

    def test_unknown_auete_artifact_explicit(self):
        data=b'synthetic unknown Auete';digest=hashlib.sha256(data).hexdigest()
        (self.root/'imports'/(digest+'.jar')).write_bytes(data)
        self.assertEqual(self.request({'version':1,'adapter':'auete-v1','artifactHash':digest}),
                         (422,{'error':'guard_artifact_unsupported'}))

class ReviewedLiteralChecks(unittest.TestCase):
    setUp=guard.ReaderChecks.setUp
    tearDown=guard.ReaderChecks.tearDown
    run_reader=guard.ReaderChecks.run_reader

    def test_exact_code_fingerprint_and_known_unit(self):
        data,code=guard.fixture_dex();size=int.from_bytes(data[code+12:code+16],'little')
        digest=hashlib.sha256(data[code:code+16+size*2]).hexdigest()
        expression="new x.Dex(b).reviewedLiteral('Lfixture/Source;','<init>','"+digest+"',3,'yq')"
        self.assertEqual(self.run_reader(data,expression)['value'],'http://127.0.0.1:9')
        self.assertIn('error',self.run_reader(data,expression.replace(digest,'0'*64)))
        self.assertIn('error',self.run_reader(data,expression.replace(",3,'yq'",",0,'yq'")))
        self.assertIn('error',self.run_reader(data,expression.replace("'yq'","'SN'")))

if __name__=='__main__':
    unittest.main(verbosity=2)
