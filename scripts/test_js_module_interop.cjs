// Exercise the exact shared loader shipped to Android and Flutter, without Node APIs inside source modules.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const runtime = vm.createContext({});
vm.runInContext(fs.readFileSync(path.join(__dirname, '../android/src/main/assets/js-host/module-interop.js'), 'utf8'), runtime);
vm.runInContext(String.raw`
const modules = {
 'main.js': "const data=require('./data.json'); const a=require('./a.js'); module.exports={home(){return data},detail(id){return {id, cycle:a.cycle}},play(flag,id){return {url:id,flag}}};",
 'data.json': '{"list":[{"vod_id":"fixture"}]}',
 'a.js': "exports.name='a';exports.cycle=require('./b.js').parent;",
 'b.js': "exports.parent=require('./a.js').name;"
};
globalThis.spider = __movieSelectSpider(__movieLoadCommonJS('main.js', (name) => {
 name=name.replace(/^\.\//,''); if(!modules[name])throw new Error('missing dependency');
 return {name,source:modules[name]};
}));
`, runtime);
assert.equal(vm.runInContext('spider.home().list[0].vod_id', runtime), 'fixture');
assert.equal(vm.runInContext("spider.detail('42').cycle", runtime), 'a');
assert.equal(vm.runInContext("spider.detail('42').id", runtime), '42');
assert.equal(vm.runInContext("spider.play('Main','https://media.example/clip.mp4').url", runtime), 'https://media.example/clip.mp4');
assert.equal(vm.runInContext("__movieSelectSpider({default:spider})===spider", runtime), true);
assert.throws(() => vm.runInContext('__movieSelectSpider(null)', runtime), /no Spider export/);
assert.throws(() => vm.runInContext("__movieLoadCommonJS('bad.js', () => ({name:'bad.js',source:'module.exports = ;'}))", runtime), /Unexpected token/);
console.log('PASS shared CommonJS JSON/cycle/home/detail/play/default/error boundaries');

// This checks the shipped settlement protocol; JNI job pumping has a separate Android probe.
(async () => {
  vm.runInContext("__movieBeginCall(async()=>{await Promise.resolve();return {list:[{vod_id:'async'}]}})", runtime);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(JSON.parse(vm.runInContext('__movieFinishCall()', runtime)).list[0].vod_id, 'async');
  vm.runInContext("__movieBeginCall(async()=>{throw new Error('fixture rejection')})", runtime);
  await new Promise(resolve => setImmediate(resolve));
  assert.throws(() => vm.runInContext('__movieFinishCall()', runtime), /fixture rejection/);
  vm.runInContext('__movieBeginCall(()=>new Promise(()=>{}))', runtime);
  assert.throws(() => vm.runInContext('__movieFinishCall()', runtime), /did not settle/);
  vm.runInContext("__movieBeginCall(()=>{throw new Error('sync failure')})", runtime);
  assert.throws(() => vm.runInContext('__movieFinishCall()', runtime), /sync failure/);
  vm.runInContext('__movieBeginCall(()=>{const v={};v.self=v;return v})', runtime);
  assert.throws(() => vm.runInContext('__movieFinishCall()', runtime), /circular/i);
  vm.runInContext('__movieBeginCall(()=>({ok:true}))', runtime);
  assert.equal(JSON.parse(vm.runInContext('__movieFinishCall()', runtime)).ok, true);
  console.log('PASS shared Promise settlement/rejection/pending/serialization/recovery boundaries');
})().catch(error => { console.error(error); process.exitCode = 1; });
