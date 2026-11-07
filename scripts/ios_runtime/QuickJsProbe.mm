// Exercise the production QuickJS bridge and Python assets on the iOS CPU.
#import <Foundation/Foundation.h>
#include "quickjs/quickjs.h"
#include <cstring>
extern "C" {
  JSRuntime *jsNewRuntime(void *(*channel)(JSContext *, size_t, void *), int64_t timeout);
  JSContext *jsNewContext(JSRuntime *);
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
    for(NSString *name in @[@"skulpt.min.js",@"skulpt-stdlib.js",@"spider.js"]){
      NSString *file=[[assets stringByAppendingPathComponent:@"python-host"] stringByAppendingPathComponent:name];
      NSString *source=[NSString stringWithContentsOfFile:file encoding:NSUTF8StringEncoding error:nil];
      if(!source) @throw [NSException exceptionWithName:@"QuickJsProbe" reason:@"python_asset_missing" userInfo:nil];
      evaluate(ctx,source,JS_EVAL_TYPE_GLOBAL,name);
    }
    // HTTP response is synthetic here; the separate Node test uses a real socket.
    NSString *response=@"{\"list\":[{\"vod_id\":\"one\",\"vod_name\":\"Python HTTP\"}]}";
    NSString *encoded=[[response dataUsingEncoding:NSUTF8StringEncoding] base64EncodedStringWithOptions:0];
    evaluate(ctx,[NSString stringWithFormat:@"globalThis.binaryBodySeen=false;globalThis.req=async(url,options={})=>{if(url.endsWith('/upload')){if(options.bodyBase64!=='AP+AQQ=='||'data' in options||'body' in options)throw Error('binary request bridge');binaryBodySeen=true;}return {code:200,url,headers:{'Content-Type':'application/json'},content:options.buffer===2?%@:%@};};",literal(encoded),literal(response)]);
    NSString *python=@"import base64\nimport requests\nfrom helper import title\nfrom base.spider import Spider as Base\nclass Spider(Base):\n    def homeContent(self, filter):\n        jar=requests.Session().cookies\n        jar.set('sid','fixture',domain='fixture.invalid',path='/auth',secure=True)\n        cookie=list(jar)[0]\n        return {'list':[{'vod_id':'one','vod_name':title}], 'cookie_ok':cookie.name=='sid' and cookie.value=='fixture' and cookie.domain=='fixture.invalid' and cookie.path=='/auth' and cookie.secure and dict(jar)=={'sid':'fixture'}}\n    def searchContent(self, key, quick, pg='1'):\n        return self.fetch('https://fixture.invalid/data').json()\n    def playerContent(self, flag, id, vip):\n        if list(base64.urlsafe_b64decode('AP8q')) != [0,255,42]: raise ValueError('bytes')\n        requests.post('https://fixture.invalid/upload',data=bytes([0,255,128,65]))\n        return {'url':'https://fixture.invalid/video.mp4'}\n";
    evaluate(ctx,[NSString stringWithFormat:@"globalThis.py=createPythonSpider(%@,%@);globalThis.pyDone=false;globalThis.pyFailed=false;Promise.resolve(py.init('')).then(async()=>{globalThis.pyHome=await py.home(true);globalThis.pySearch=await py.search('test',false,1);globalThis.pyPlay=await py.play('line','one',[]);pyDone=true;}).catch(error=>{globalThis.pyError=String(error);pyFailed=true;pyDone=true;});",literal(python),literal(@{@"helper.py":@"title='Python 本机模块'\n"})]);
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
    [checks addObject:@{@"name":@"python_native_local_module",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pyHome.list[0].vod_name==='Python 本机模块'"))}];
    [checks addObject:@{@"name":@"python_native_async_response",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pySearch.list[0].vod_name==='Python HTTP'"))}];
    [checks addObject:@{@"name":@"python_native_binary_play_result",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pyPlay.url==='https://fixture.invalid/video.mp4'"))}];
    [checks addObject:@{@"name":@"python_native_cookie_objects",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&pyHome.cookie_ok===true"))}];
    [checks addObject:@{@"name":@"python_native_binary_request_bridge",@"passed":@(boolean(ctx,@"pyDone&&!pyFailed&&binaryBodySeen"))}];
  } @catch(NSException *error) {
    [checks addObject:@{@"name":error.reason?:@"quickjs_exception",@"passed":@NO}];
    return checks;
  } @finally {jsFreeContext(ctx);jsFreeRuntime(rt);}
  rt=jsNewRuntime(channel,100);ctx=jsNewContext(rt);
  @try {
    const char *loop="while(true){}";
    JSValue *value=jsEval(ctx,loop,strlen(loop),"deadline.js",JS_EVAL_TYPE_GLOBAL);
    [checks addObject:@{@"name":@"quickjs_native_deadline",@"passed":@(JS_IsException(*value))}];
    jsFreeValue(ctx,value,1);
    JSValue error=JS_GetException(ctx);JS_FreeValue(ctx,error);
  } @finally {jsFreeContext(ctx);jsFreeRuntime(rt);}
  return checks;
}
