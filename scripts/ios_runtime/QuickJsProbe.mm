// Exercise the production QuickJS bridge and Python assets on the iOS CPU.
#import <Foundation/Foundation.h>
#include "quickjs/quickjs.h"
#include <cstring>
extern "C" {
  JSRuntime *jsNewRuntime(void *(*channel)(JSContext *, size_t, void *), int64_t timeout);
  JSContext *jsNewContext(JSRuntime *);
  void jsSetOperationTimeout(JSRuntime *, int64_t timeout);
  JSValue *jsEval(JSContext *, const char *, size_t, const char *, int32_t);
  int32_t jsExecutePendingJob(JSRuntime *);
  void jsFreeValue(JSContext *, JSValue *, int32_t);
  void jsFreeContext(JSContext *);
  void jsFreeRuntime(JSRuntime *);
}
static void *channel(JSContext *ctx, size_t kind, void *value) { return nullptr; }
static void evaluate(JSContext *ctx, NSString *source, int flags=JS_EVAL_TYPE_GLOBAL, NSString *name=@"ios-fixture.js") {
  const char *code=source.UTF8String;
  JSValue *value=jsEval(ctx,code,strlen(code),name.UTF8String,flags);
  BOOL failed=JS_IsException(*value);jsFreeValue(ctx,value,1);
  if(failed){
    JSValue error=JS_GetException(ctx);const char *text=JS_ToCString(ctx,error);
    NSString *message=text?[NSString stringWithUTF8String:text]:@"unknown";
    if(text)JS_FreeCString(ctx,text);JS_FreeValue(ctx,error);
    @throw [NSException exceptionWithName:@"QuickJsProbe" reason:[NSString stringWithFormat:@"quickjs_evaluation [%@]: %@",name,message] userInfo:nil];
  }
}
static BOOL boolean(JSContext *ctx, NSString *source) {
  const char *code=source.UTF8String;
  JSValue *value=jsEval(ctx,code,strlen(code),"ios-assert.js",JS_EVAL_TYPE_GLOBAL);
  BOOL result=!JS_IsException(*value)&&JS_ToBool(ctx,*value)==1;
  jsFreeValue(ctx,value,1);return result;
}
static NSString *diagnostic(JSContext *ctx, NSString *source) {
  const char *code=source.UTF8String;
  JSValue *value=jsEval(ctx,code,strlen(code),"ios-diagnostic.js",JS_EVAL_TYPE_GLOBAL);
  const char *text=JS_IsException(*value)?nullptr:JS_ToCString(ctx,*value);
  NSString *result=text?[NSString stringWithUTF8String:text]:@"evaluation failed";
  if(text)JS_FreeCString(ctx,text);jsFreeValue(ctx,value,1);
  return result.length>8192?[result substringToIndex:8192]:result;
}
static NSString *literal(id value) {
  NSData *data=[NSJSONSerialization dataWithJSONObject:value options:NSJSONWritingFragmentsAllowed error:nil];
  return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
}
NSArray *runQuickJsProbe(NSString *assets) {
  NSMutableArray *checks=[NSMutableArray array];
  JSRuntime *rt=jsNewRuntime(channel,10000);JSContext *ctx=jsNewContext(rt);
  JS_SetMaxStackSize(rt,512*1024);JS_SetMemoryLimit(rt,48*1024*1024);
  @try {
    evaluate(ctx,@"globalThis.global=globalThis;globalThis.window=globalThis;globalThis.self=globalThis;globalThis.console={log(){},warn(){},error(){}};");
    evaluate(ctx,@"export const title='iOS 模块'; globalThis.moduleTitle=title;",JS_EVAL_TYPE_MODULE);
    while(jsExecutePendingJob(rt)>0){}
    [checks addObject:@{@"name":@"quickjs_native_es_module",@"passed":@(boolean(ctx,@"moduleTitle==='iOS 模块'"))}];
    NSString *dependencies=[NSString stringWithContentsOfFile:[assets stringByAppendingPathComponent:@"runtime/drpy-dependencies.js"] encoding:NSUTF8StringEncoding error:nil];
    if(!dependencies) @throw [NSException exceptionWithName:@"QuickJsProbe" reason:@"html_dependency_missing" userInfo:nil];
    evaluate(ctx,[@"globalThis.__moviePythonHtmlParser=function(){const navigator={appName:'Netscape'};\n" stringByAppendingFormat:@"%@\nglobalThis.__moviePythonHtmlParser=()=>DRPYDeps.cheerio;return DRPYDeps.cheerio;};", dependencies],JS_EVAL_TYPE_GLOBAL,@"python-parser-loader.js");
    for(NSString *name in @[@"skulpt.min.js",@"skulpt-stdlib.js",@"html.js",@"regex.js",@"json.js",@"io.js",@"spider.js"]){
      NSString *file=[[assets stringByAppendingPathComponent:@"python-host"] stringByAppendingPathComponent:name];
      NSString *source=[NSString stringWithContentsOfFile:file encoding:NSUTF8StringEncoding error:nil];
      if(!source) @throw [NSException exceptionWithName:@"QuickJsProbe" reason:@"python_asset_missing" userInfo:nil];
      evaluate(ctx,source,JS_EVAL_TYPE_GLOBAL,name);
    }
    // HTTP response is synthetic here; the separate Node test uses a real socket.
    // Asset setup is not a Python operation. Start the same bounded operation
    // clock immediately before invoking it, as subsequent probes already do.
    jsSetOperationTimeout(rt,8000);
    NSDate *pythonCallStarted=[NSDate date];
    NSString *response=@"{\"list\":[{\"vod_id\":\"one\",\"vod_name\":\"Python HTTP\"}]}";
    NSString *encoded=[[response dataUsingEncoding:NSUTF8StringEncoding] base64EncodedStringWithOptions:0];
    evaluate(ctx,[NSString stringWithFormat:@"globalThis.binaryBodySeen=false;globalThis.req=async(url,options={})=>{if(url.endsWith('/upload')){if(options.bodyBase64!=='AP+AQQ=='||'data' in options||'body' in options)throw Error('binary request bridge');binaryBodySeen=true;}return {code:200,url,headers:{'Content-Type':'application/json'},content:options.buffer===2?%@:%@};};",literal(encoded),literal(response)],JS_EVAL_TYPE_GLOBAL,@"python-request-fixture.js");
    NSString *python=@"import base64\nimport requests\nfrom helper import title\nfrom base.spider import Spider as Base\nclass Spider(Base):\n    def homeContent(self, filter):\n        jar=requests.Session().cookies\n        jar.set('sid','fixture',domain='fixture.invalid',path='/auth',secure=True)\n        cookie=list(jar)[0]\n        return {'list':[{'vod_id':'one','vod_name':title}], 'cookie_ok':cookie.name=='sid' and cookie.value=='fixture' and cookie.domain=='fixture.invalid' and cookie.path=='/auth' and cookie.secure and dict(jar)=={'sid':'fixture'}}\n    def searchContent(self, key, quick, pg='1'):\n        return self.fetch('https://fixture.invalid/data').json()\n    def playerContent(self, flag, id, vip):\n        if list(base64.urlsafe_b64decode('AP8q')) != [0,255,42]: raise ValueError('bytes')\n        requests.post('https://fixture.invalid/upload',data=bytes([0,255,128,65]))\n        return {'url':'https://fixture.invalid/video.mp4'}\n";
    python=[python stringByReplacingOccurrencesOfString:@"    def homeContent(self, filter):\n" withString:@"    def __init__(self):\n        self.boot_title = self.fetch('https://fixture.invalid/bootstrap').json()['list'][0]['vod_name']\n    def init(self, ext):\n        self.boot_ready = self.boot_title == 'Python HTTP'\n    def homeContent(self, filter):\n        assert self.boot_ready\n"];
    python=[python stringByAppendingString:@"\nfrom remotehelper import remote_title\nassert remote_title == 'native async helper'\n"];
    evaluate(ctx,[NSString stringWithFormat:@"globalThis.pythonRemoteReads=0;globalThis.py=createPythonSpider(%@,%@,async name=>{pythonRemoteReads++;return name==='remotehelper.py'?\"remote_title = 'native async helper'\":null;});globalThis.pyDone=false;globalThis.pyFailed=false;Promise.resolve(py.init('')).then(async()=>{globalThis.pyHome=await py.home(true);Sk.execStart-=9000;Sk.lastYield-=9000;globalThis.pySearch=await py.search('test',false,1);globalThis.pyPlay=await py.play('line','one',[]);pyDone=true;}).catch(error=>{globalThis.pyError=String(error);pyFailed=true;pyDone=true;});",literal(python),literal(@{@"helper.py":@"title='Python 本机模块'\n"})],JS_EVAL_TYPE_GLOBAL,@"python-bootstrap.js");
    NSDate *until=[NSDate dateWithTimeIntervalSinceNow:8];
    while(!boolean(ctx,@"pyDone")&&until.timeIntervalSinceNow>0){
      while(jsExecutePendingJob(rt)>0){} [NSThread sleepForTimeInterval:.001];
    }
    if(boolean(ctx,@"pyFailed")){
      JSValue global=JS_GetGlobalObject(ctx),error=JS_GetPropertyStr(ctx,global,"pyError");
      const char *text=JS_ToCString(ctx,error);
      NSString *message=text?[NSString stringWithUTF8String:text]:@"unknown";
      if(text)JS_FreeCString(ctx,text);JS_FreeValue(ctx,error);JS_FreeValue(ctx,global);
      @throw [NSException exceptionWithName:@"QuickJsProbe" reason:[@"python_execution: " stringByAppendingString:message] userInfo:nil];
    }
    [checks addObject:@{@"name":@"python_native_local_module",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pyHome.list[0].vod_name==='Python 本机模块'")),@"elapsed_ms":@(-pythonCallStarted.timeIntervalSinceNow*1000)}];
    [checks addObject:@{@"name":@"python_native_async_module",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pythonRemoteReads>0"))}];
    [checks addObject:@{@"name":@"python_native_async_constructor",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pyHome.list[0].vod_name==='Python 本机模块'"))}];
    [checks addObject:@{@"name":@"python_native_async_response",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pySearch.list[0].vod_name==='Python HTTP'"))}];
    [checks addObject:@{@"name":@"python_native_binary_play_result",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pyPlay.url==='https://fixture.invalid/video.mp4'"))}];
    [checks addObject:@{@"name":@"python_native_cookie_objects",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pyHome.cookie_ok===true"))}];
    [checks addObject:@{@"name":@"python_native_binary_request_bridge",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&binaryBodySeen"))}];
    [checks addObject:@{@"name":@"python_native_renewed_operation_clock",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&Sk.execLimit===8000&&pySearch.list[0].vod_name==='Python HTTP'"))}];
    for(NSString *contract in @[@"html",@"json",@"io"]) {
    NSString *htmlSource=[NSString stringWithContentsOfFile:[assets stringByAppendingPathComponent:[NSString stringWithFormat:@"python_%@_contract.py",contract]] encoding:NSUTF8StringEncoding error:nil];
    NSData *htmlExpectedData=[NSData dataWithContentsOfFile:[assets stringByAppendingPathComponent:[NSString stringWithFormat:@"python_%@_expected.json",contract]]];
    NSString *htmlExpectedText=htmlExpectedData?[[NSString alloc] initWithData:htmlExpectedData encoding:NSUTF8StringEncoding]:nil;
    NSDictionary *htmlExpected=htmlExpectedData?[NSJSONSerialization JSONObjectWithData:htmlExpectedData options:0 error:nil]:nil;
    NSUInteger expectedCount=[contract isEqualToString:@"html"]?21:[contract isEqualToString:@"json"]?28:29;
    if(!htmlSource||htmlExpected.count!=expectedCount) @throw [NSException exceptionWithName:@"QuickJsProbe" reason:[contract stringByAppendingString:@"_contract_fixture_missing"] userInfo:nil];
    jsSetOperationTimeout(rt,8000);
    // Keep the oracle's original UTF-8 text: Foundation's parsed string path
    // removes a leading U+FEFF inside an expected value on iOS. Re-serializing
    // that dictionary corrupts the BOM error case before the comparison.
    evaluate(ctx,[NSString stringWithFormat:@"globalThis.htmlDone=false;globalThis.htmlError='';globalThis.htmlSpider=createPythonSpider(%@);Promise.resolve(htmlSpider.init('')).then(()=>htmlSpider.home(true)).then(value=>{globalThis.htmlResult=value;htmlDone=true;}).catch(error=>{htmlError=String(error);htmlDone=true;});globalThis.htmlExpected=%@;",literal(htmlSource),htmlExpectedText],JS_EVAL_TYPE_GLOBAL,@"python-oracle.js");
    until=[NSDate dateWithTimeIntervalSinceNow:8];
    while(!boolean(ctx,@"htmlDone")&&until.timeIntervalSinceNow>0){
      while(jsExecutePendingJob(rt)>0){} [NSThread sleepForTimeInterval:.001];
    }
    if([contract isEqualToString:@"json"]) [checks addObject:@{@"name":@"python_json_oracle_utf8_preserved",@"passed":@(boolean(ctx,@"htmlExpected.errors[11][0]==='\\ufeff{}'"))}];
    for(NSString *key in htmlExpected){
      NSString *condition=[NSString stringWithFormat:@"htmlDone&&!htmlError&&((actual,expected)=>typeof expected==='object'&&!Array.isArray(expected)&&expected!==null?Object.keys(expected).every(k=>JSON.stringify(actual[k])===JSON.stringify(expected[k])):JSON.stringify(actual)===JSON.stringify(expected))(htmlResult[%@],htmlExpected[%@])",literal(key),literal(key)];
      BOOL passed=boolean(ctx,condition);
      NSMutableDictionary *check=[@{@"name":[NSString stringWithFormat:@"python_native_%@_%@",contract,key],@"passed":@(passed)} mutableCopy];
      if(!passed) check[@"diagnostic"]=diagnostic(ctx,[NSString stringWithFormat:@"JSON.stringify({done:htmlDone,error:htmlError,actual:typeof htmlResult==='undefined'?null:htmlResult[%@],expected:htmlExpected[%@]})",literal(key),literal(key)]);
      [checks addObject:check];
    }
    }
  } @catch(NSException *error) {
    [checks addObject:@{@"name":error.reason?:@"quickjs_exception",@"passed":@NO}];
    return checks;
  } @finally {jsFreeContext(ctx);jsFreeRuntime(rt);}
  rt=jsNewRuntime(channel,100);ctx=jsNewContext(rt);
  @try {
    evaluate(ctx,@"globalThis.retainedCounter=1;");
    [NSThread sleepForTimeInterval:.15];
    jsSetOperationTimeout(rt,1000);
    evaluate(ctx,@"retainedCounter++;");
    [checks addObject:@{@"name":@"quickjs_native_renewed_operation_deadline",@"passed":@(boolean(ctx,@"retainedCounter===2"))}];
    jsSetOperationTimeout(rt,100);
    const char *loop="while(true){}";
    JSValue *value=jsEval(ctx,loop,strlen(loop),"deadline.js",JS_EVAL_TYPE_GLOBAL);
    [checks addObject:@{@"name":@"quickjs_native_deadline",@"passed":@(JS_IsException(*value))}];
    jsFreeValue(ctx,value,1);
    JSValue error=JS_GetException(ctx);JS_FreeValue(ctx,error);
  } @finally {jsFreeContext(ctx);jsFreeRuntime(rt);}
  return checks;
}
