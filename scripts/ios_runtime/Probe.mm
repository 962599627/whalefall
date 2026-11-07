// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
// A simulator-only app that links the production engine and runs production JS.
#import <UIKit/UIKit.h>
#import <CommonCrypto/CommonDigest.h>
#import "CatpawRuntimeEngine.h"

static NSMutableArray *checks;
static void check(BOOL ok, NSString *name) {
  [checks addObject:@{@"name":name,@"passed":@(ok)}];
  if (!ok) @throw [NSException exceptionWithName:@"ProbeFailure" reason:name userInfo:nil];
}
static NSData *jsonData(id value) {
  return [NSJSONSerialization dataWithJSONObject:value options:0 error:nil];
}
static id json(NSData *value) {
  return [NSJSONSerialization JSONObjectWithData:value options:NSJSONReadingFragmentsAllowed error:nil];
}
static NSData *request(NSString *address, NSString *method, id body, NSDictionary *headers) {
  NSMutableURLRequest *req=[NSMutableURLRequest requestWithURL:[NSURL URLWithString:address]];
  req.HTTPMethod=method; req.timeoutInterval=25;
  if (body) { req.HTTPBody=jsonData(body); [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"]; }
  for (NSString *key in headers) [req setValue:headers[key] forHTTPHeaderField:key];
  dispatch_semaphore_t done=dispatch_semaphore_create(0);
  __block NSData *bytes=nil; __block NSError *failure=nil; __block NSInteger status=0;
  NSURLSession *session=[NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.ephemeralSessionConfiguration];
  [[session dataTaskWithRequest:req completionHandler:^(NSData *data,NSURLResponse *response,NSError *error){
    bytes=data;failure=error;status=((NSHTTPURLResponse *)response).statusCode;
    dispatch_semaphore_signal(done);
  }] resume];
  long timedOut=dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,30*NSEC_PER_SEC));
  [session invalidateAndCancel];
  if (timedOut||failure||status<200||status>=300)
    @throw [NSException exceptionWithName:@"TransportFailure" reason:@"local HTTP request failed" userInfo:nil];
  return bytes;
}
static NSString *install(NSString *root,NSString *name,NSData *data) {
  unsigned char digest[CC_SHA256_DIGEST_LENGTH]; CC_SHA256(data.bytes,(CC_LONG)data.length,digest);
  NSMutableString *key=[NSMutableString string];
  for(int i=0;i<CC_SHA256_DIGEST_LENGTH;i++)[key appendFormat:@"%02x",digest[i]];
  NSString *folder=[root stringByAppendingPathComponent:key];
  [[NSFileManager defaultManager] createDirectoryAtPath:folder withIntermediateDirectories:YES attributes:nil error:nil];
  [data writeToFile:[folder stringByAppendingPathComponent:name] atomically:YES];
  return key;
}
static id call(NSString *base,NSString *key,NSString *method,NSArray *args) {
  return json(request([NSString stringWithFormat:@"%@/%@/api/drpy",base,key],@"POST",
    @{@"version":@1,@"method":method,@"args":args},@{}));
}

@interface ProbeDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic,strong) UIWindow *window;
@end
@implementation ProbeDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
  self.window=[[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
  UIViewController *view=[UIViewController new];view.view.backgroundColor=UIColor.blackColor;
  self.window.rootViewController=view;[self.window makeKeyAndVisible];
  dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ [self runProbe]; });
  return YES;
}
- (void)runProbe {
  checks=[NSMutableArray array];NSString *failure=nil;NSString *runtimeVersion=@"";
  @try {
    NSFileManager *fm=NSFileManager.defaultManager;
    NSString *root=[[NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory,NSUserDomainMask,YES) firstObject]
      stringByAppendingPathComponent:NSUUID.UUID.UUIDString];
    [fm createDirectoryAtPath:root withIntermediateDirectories:YES attributes:nil error:nil];
    NSString *assets=[NSBundle.mainBundle.resourcePath stringByAppendingPathComponent:@"runtime"];
    for(NSString *name in [fm contentsOfDirectoryAtPath:assets error:nil])
      [fm copyItemAtPath:[assets stringByAppendingPathComponent:name] toPath:[root stringByAppendingPathComponent:name] error:nil];
    NSString *script=[root stringByAppendingPathComponent:@"host.cjs"],*ready=[root stringByAppendingPathComponent:@"ready.json"];
    NSError *error=nil;
    check([CatpawRuntimeEngine startWithRoot:root script:script ready:ready error:&error],@"production_engine_start");
    NSDictionary *state=nil;NSDate *until=[NSDate dateWithTimeIntervalSinceNow:15];
    while([until timeIntervalSinceNow]>0){
      NSData *data=[NSData dataWithContentsOfFile:ready];
      if(data){state=json(data);if(state[@"port"]&&state[@"secret"])break;}
      [NSThread sleepForTimeInterval:.04];
    }
    check(state[@"port"]!=nil,@"embedded_node_ready");
    NSString *base=[NSString stringWithFormat:@"http://127.0.0.1:%@/%@",state[@"port"],state[@"secret"]];
    NSDictionary *health=json(request([base stringByAppendingString:@"/health"],@"GET",nil,@{}));
    runtimeVersion=health[@"runtime"]?:@"";
    check([runtimeVersion hasPrefix:@"18.20.4"],@"actual_node_18_runtime");
    NSString *fixture=[NSBundle.mainBundle.resourcePath stringByAppendingPathComponent:@"fixture.cjs"];
    NSString *cat=install(root,@"index.cjs",[NSData dataWithContentsOfFile:fixture]);
    NSString *catBase=[NSString stringWithFormat:@"%@/%@",base,cat];
    NSDictionary *catalog=json(request([catBase stringByAppendingString:@"/t4"],@"GET",nil,@{}));
    check([catalog[@"sites"] count]==1&&[catalog[@"starts"] intValue]==1,@"catpaw_worker_catalog");
    NSData *fetched=request([catBase stringByAppendingString:@"/api/fetch"],@"GET",nil,@{});
    const unsigned char expected[]={0,1,255,42};
    check([fetched isEqualToData:[NSData dataWithBytes:expected length:4]],@"jitless_fetch_binary");
    NSString *rule=@"var rule={title:'iOS fixture',host:'https://fixture.invalid',class_name:'电影',class_url:'films',url:'/category/fyclass',searchUrl:'/search?wd=**',searchable:2,预处理:'js:require(\"./helpers/entry.js\");',一级:'js:VODS=[{vod_id:\"one\",vod_name:fixtureTitle}];',搜索:'js:VODS=[{vod_id:\"one\",vod_name:fixtureTitle}];',二级:'js:VOD={vod_name:fixtureTitle,vod_play_from:\"demo\",vod_play_url:\"第一集$one\"};',play_parse:true,lazy:'js:input={parse:0,url:getProxyUrl()+\"&kind=playlist\"};',proxy_rule:'js:input=input.kind===\"key\"?[200,\"application/octet-stream\",[0,1,255,42]]:[200,\"application/vnd.apple.mpegurl\",\"#EXTM3U\\n#EXT-X-VERSION:3\\n\"];'};";
    NSDictionary *manifest=@{@"kind":@"drpy",@"version":@1,@"runtime":@"drpy2-ca23ffdb-1",
      @"sourceId":[@"a" stringByPaddingToLength:64 withString:@"a" startingAtIndex:0],
      @"origin":@"pkg://archive/main.js",@"rule":rule,@"headers":@{},@"requestTimeoutMs":@1000,
      @"modules":@{@"pkg://archive/helpers/entry.js":@"require('./nested/title.js');",
        @"pkg://archive/helpers/nested/title.js":@"globalThis.fixtureTitle='iOS 本机影片';"}};
    NSString *drpy=install(root,@"drpy.json",jsonData(manifest));
    NSDictionary *home=call(base,drpy,@"home",@[@YES]);
    check([home[@"class"] count]==1,@"drpy_home");
    NSDictionary *category=call(base,drpy,@"category",@[@"films",@1,@NO,@{}]);
    check([category[@"list"][0][@"vod_name"] isEqual:@"iOS 本机影片"],@"drpy_local_nested_modules");
    NSDictionary *search=call(base,drpy,@"search",@[@"本机",@NO,@1]);
    check([search[@"list"] count]==1,@"drpy_search");
    NSDictionary *detail=call(base,drpy,@"detail",@[@"one"]);
    check([detail[@"list"][0][@"vod_play_url"] length]>0,@"drpy_detail_episodes");
    NSDictionary *play=call(base,drpy,@"play",@[@"demo",@"one",@[]]);
    NSString *playlist=play[@"url"];
    NSString *text=[[NSString alloc] initWithData:request(playlist,@"GET",nil,@{}) encoding:NSUTF8StringEncoding];
    check([text hasPrefix:@"#EXTM3U"],@"drpy_local_proxy_playlist");
    NSString *keyURL=[playlist stringByReplacingOccurrencesOfString:@"kind=playlist" withString:@"kind=key"];
    NSData *range=request(keyURL,@"GET",nil,@{@"Range":@"bytes=1-2"});
    check([range isEqualToData:[NSData dataWithBytes:expected+1 length:2]],@"drpy_proxy_binary_range");
    NSDictionary *reset=json(request([base stringByAppendingString:@"/_reset"],@"POST",@{},@{}));
    check([reset[@"reset"] boolValue],@"embedded_reset_without_process_exit");
    check([CatpawRuntimeEngine startWithRoot:root script:script ready:ready error:&error],@"native_reopen_same_root");
    catalog=json(request([catBase stringByAppendingString:@"/t4"],@"GET",nil,@{}));
    check([catalog[@"starts"] intValue]==2,@"catpaw_worker_recreated_with_saved_state");
    home=call(base,drpy,@"home",@[@YES]);
    check([home[@"class"] count]==1,@"drpy_reopen");
  } @catch(NSException *error) { failure=error.reason?:@"probe_exception"; }
  NSDictionary *report=@{@"passed":@(failure==nil),@"checks":checks,@"failure":failure?:@"",
    @"runtime":runtimeVersion,@"scope":@"iOS simulator, production Node engine and scripts; no Flutter UI or media decoder acceptance"};
  NSString *folder=[NSSearchPathForDirectoriesInDomains(NSDocumentDirectory,NSUserDomainMask,YES) firstObject];
  [NSFileManager.defaultManager createDirectoryAtPath:folder withIntermediateDirectories:YES attributes:nil error:nil];
  [jsonData(report) writeToFile:[folder stringByAppendingPathComponent:@"result.json"] atomically:YES];
}
@end
int main(int argc,char **argv){@autoreleasepool{return UIApplicationMain(argc,argv,nil,NSStringFromClass(ProbeDelegate.class));}}
